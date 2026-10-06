package com.nexus.backend.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.backend.domain.ai.AiConversation;
import com.nexus.backend.domain.ai.AiMessage;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.dto.AiAskRequest;
import com.nexus.backend.dto.AiConversationDetailResponse;
import com.nexus.backend.dto.AiConversationResponse;
import com.nexus.backend.dto.AiKnowledgeStatusResponse;
import com.nexus.backend.dto.AiReindexResponse;
import com.nexus.backend.dto.AiSourceResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.service.ai.AiEngine;
import com.nexus.backend.service.ai.AiEngineException;
import com.nexus.backend.service.ai.AiService;
import com.nexus.backend.service.ai.ConversationService;
import com.nexus.backend.service.ai.PermissionService;
import com.nexus.backend.service.ai.RetrievalService;
import com.nexus.backend.service.ai.RetrievedContext;
import com.nexus.backend.service.ai.VectorIndex;
import com.nexus.backend.service.knowledge.KnowledgeIndexService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Nexus AI endpoints.
 *
 * Pipeline per question: authenticate (JWT filter) → validate → enforce
 * conversation ownership → permission-aware retrieval → context package →
 * server-side AI engine → streamed answer + persisted citations. The answer
 * streams as Server-Sent Events ({@code meta}, {@code delta}, {@code done},
 * {@code error}) over a POST, so the browser receives tokens as they are
 * generated while the OpenAI key never leaves the server.
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);
    private static final long STREAM_TIMEOUT_MS = 120_000L;

    private final PermissionService permissionService;
    private final ConversationService conversationService;
    private final RetrievalService retrievalService;
    private final AiService aiService;
    private final AiEngine engine;
    private final KnowledgeIndexService knowledgeIndexService;
    private final VectorIndex vectorIndex;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService streamExecutor;

    public AiController(
            PermissionService permissionService,
            ConversationService conversationService,
            RetrievalService retrievalService,
            AiService aiService,
            AiEngine engine,
            KnowledgeIndexService knowledgeIndexService,
            VectorIndex vectorIndex) {
        this.permissionService = permissionService;
        this.conversationService = conversationService;
        this.retrievalService = retrievalService;
        this.aiService = aiService;
        this.engine = engine;
        this.knowledgeIndexService = knowledgeIndexService;
        this.vectorIndex = vectorIndex;
        this.streamExecutor = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(32),
            runnable -> {
                Thread thread = new Thread(runnable, "ai-chat-stream");
                thread.setDaemon(true);
                return thread;
            });
    }

    // ---------- conversations ----------

    @GetMapping("/conversations")
    public List<AiConversationResponse> listConversations() {
        User user = permissionService.currentUser();
        return conversationService.list(user);
    }

    @GetMapping("/conversations/{id}")
    public AiConversationDetailResponse getConversation(@PathVariable UUID id) {
        User user = permissionService.currentUser();
        return conversationService.detail(id, user);
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(@PathVariable UUID id) {
        User user = permissionService.currentUser();
        conversationService.delete(id, user);
        return ResponseEntity.noContent().build();
    }

    // ---------- asking ----------

    /** Ask a question; creates a conversation when none is supplied. */
    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@Valid @RequestBody AiAskRequest request) {
        User user = permissionService.currentUser();

        UUID conversationId;
        List<AiMessage> history;
        if (request.conversationId() == null) {
            AiConversation created = conversationService.create(user, request.question());
            conversationId = created.getId();
            history = List.of();
        } else {
            conversationId = request.conversationId();
            // Ownership check on the request thread so a wrong id 404s before streaming.
            history = conversationService.history(conversationId, user);
        }

        return startStream(emitter ->
            answerLoop(emitter, conversationId, request.question(), history,
                request.projectId(), user, true));
    }

    /** Re-ask the last question of a conversation with freshly retrieved context. */
    @PostMapping(value = "/conversations/{id}/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter regenerate(@PathVariable UUID id) {
        User user = permissionService.currentUser();
        conversationService.history(id, user); // ownership check before streaming

        return startStream(emitter -> {
            try {
                ConversationService.RegeneratePlan plan = conversationService.prepareRegenerate(id, user);
                answerLoop(emitter, id, plan.question(), plan.history(), null, user, false);
            } catch (ValidationException | ResourceNotFoundException e) {
                sendError(emitter, e.getMessage());
            }
        });
    }

    // ---------- knowledge status / maintenance ----------

    @GetMapping("/knowledge/status")
    public AiKnowledgeStatusResponse knowledgeStatus() {
        permissionService.currentUser();
        return new AiKnowledgeStatusResponse(
            engine.isConfigured(),
            engine.answerModel(),
            engine.embeddingModel(),
            knowledgeIndexService.totalChunks(),
            knowledgeIndexService.embeddedChunks(),
            vectorIndex.size(),
            knowledgeIndexService.chunksBySource());
    }

    /** Incremental re-index (ADMIN): syncs only stale/missing records unless full. */
    @PostMapping("/knowledge/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public AiReindexResponse reindex(@RequestParam(defaultValue = "false") boolean full) {
        permissionService.currentUser();
        KnowledgeIndexService.ReindexResult result = knowledgeIndexService.reindex(full);
        return new AiReindexResponse(result.sources(), result.reindexed(), result.removed(), result.vectors());
    }

    // ---------- streaming internals ----------

    private SseEmitter startStream(Consumer<SseEmitter> task) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        try {
            streamExecutor.execute(() -> task.accept(emitter));
        } catch (RejectedExecutionException e) {
            throw new AiEngineException(AiEngineException.Kind.UNAVAILABLE, "The AI assistant is at capacity");
        }
        emitter.onTimeout(() -> log.debug("AI stream timed out"));
        emitter.onError(t -> log.debug("AI stream error: {}", t.getMessage()));
        return emitter;
    }

    private void answerLoop(
            SseEmitter emitter,
            UUID conversationId,
            String question,
            List<AiMessage> history,
            Long projectId,
            User user,
            boolean persistUserMessage) {
        long started = System.currentTimeMillis();
        try {
            if (persistUserMessage) {
                conversationService.saveUserMessage(conversationId, user, question);
            }

            List<RetrievedContext> context = retrievalService.retrieve(question, projectId, user);
            List<AiSourceResponse> sources = toSources(context);

            Map<String, Object> meta = new HashMap<>();
            meta.put("conversationId", conversationId.toString());
            meta.put("sources", sources);
            send(emitter, "meta", meta);

            AiService.AiAnswer answer = aiService.ask(question, context, history,
                delta -> send(emitter, "delta", Map.of("text", delta)));

            var saved = conversationService.saveAssistantMessage(
                conversationId, answer.content(), sources, answer.mode(), answer.model());

            Map<String, Object> done = new HashMap<>();
            done.put("messageId", saved.getId().toString());
            done.put("conversationId", conversationId.toString());
            done.put("mode", answer.mode());
            done.put("model", answer.model() == null ? "" : answer.model());
            send(emitter, "done", done);
            emitter.complete();

            log.info("ai.ask userId={} conversationId={} mode={} sources={} contextChars={} durationMs={}",
                user.getId(), conversationId, answer.mode(), sources.size(),
                contextChars(context), System.currentTimeMillis() - started);
        } catch (AiEngineException e) {
            log.warn("ai.ask failed userId={} kind={}: {}", user.getId(), e.kind(), e.getMessage());
            sendError(emitter, e.userMessage());
        } catch (Exception e) {
            log.error("ai.ask unexpected failure userId={}: {}", user.getId(), e.toString());
            sendError(emitter, "Something went wrong while answering. Please try again.");
        }
    }

    private void send(SseEmitter emitter, String event, Map<String, ?> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(data)));
        } catch (IOException | IllegalStateException e) {
            throw new UncheckedIOException(new IOException("AI stream closed", e));
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                .data(mapper.writeValueAsString(Map.of("message", message))));
        } catch (Exception ignored) {
            // The receiver is already gone; nothing left to notify.
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // Already completed.
        }
    }

    private List<AiSourceResponse> toSources(List<RetrievedContext> context) {
        return context.stream()
            .map(item -> new AiSourceResponse(
                item.sourceType().name(),
                item.sourceId(),
                item.title(),
                item.category(),
                item.projectId(),
                item.updatedAt() == null ? null : item.updatedAt().toString()))
            .toList();
    }

    private static int contextChars(List<RetrievedContext> context) {
        int total = 0;
        for (RetrievedContext item : context) total += item.content().length();
        return total;
    }
}
