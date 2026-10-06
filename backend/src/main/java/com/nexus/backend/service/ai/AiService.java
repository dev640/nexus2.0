package com.nexus.backend.service.ai;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * The clean server-side abstraction the rest of Nexus talks to:
 * {@code aiService.ask(question, context, conversation, onDelta)}.
 *
 * It packs only the smallest relevant context plus bounded conversation
 * history, delegates to the configured {@link AiEngine} (OpenAI server-side),
 * and degrades to an honest context-only answer when no engine is configured.
 * Callers never see OpenAI request shapes, keys or endpoints.
 */
@Service
public class AiService {

    /** One generated answer with how it was produced. */
    public record AiAnswer(String content, String mode, String model) {}

    private final AiEngine engine;
    private final PromptBuilder promptBuilder;

    public AiService(AiEngine engine, PromptBuilder promptBuilder) {
        this.engine = engine;
        this.promptBuilder = promptBuilder;
    }

    /**
     * Generate an answer for a question given retrieved context and prior
     * conversation, streaming deltas through {@code onDelta} as they arrive.
     *
     * @throws AiEngineException when the engine fails before producing content
     */
    public AiAnswer ask(
            String question,
            List<RetrievedContext> context,
            List<com.nexus.backend.domain.ai.AiMessage> conversation,
            Consumer<String> onDelta) {

        if (!engine.isConfigured()) {
            String content = promptBuilder.fallbackAnswer(question, context);
            onDelta.accept(content);
            return new AiAnswer(content, "context", null);
        }

        StringBuilder partial = new StringBuilder();
        try {
            String full = engine.streamAnswer(
                promptBuilder.messages(question, context, conversation),
                delta -> {
                    partial.append(delta);
                    onDelta.accept(delta);
                });
            return new AiAnswer(full, "openai", engine.answerModel());
        } catch (AiEngineException e) {
            if (partial.length() > 0) {
                // Keep what streamed before the failure, clearly marked.
                String content = partial + "\n\n---\n*Answer interrupted — " + e.userMessage() + "*";
                return new AiAnswer(content, "openai", engine.answerModel());
            }
            throw e;
        }
    }
}
