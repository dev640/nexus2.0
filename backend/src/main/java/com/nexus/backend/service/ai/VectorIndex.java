package com.nexus.backend.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.backend.domain.knowledge.AccessScope;
import com.nexus.backend.domain.knowledge.KnowledgeChunk;
import com.nexus.backend.domain.knowledge.KnowledgeSourceType;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.repository.KnowledgeChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory vector index over the knowledge store's embeddings, so semantic
 * retrieval does not require pgvector (or any infrastructure change) and never
 * scans the database per question.
 *
 * The index is persisted in {@code knowledge_chunks.embedding}, warmed from the
 * database on startup, updated incrementally whenever a record is (re)indexed,
 * and fully refreshed on a schedule so multi-instance deployments converge.
 * Permission filtering is applied here on every hit, in addition to the SQL
 * filter used by the text search.
 */
@Component
public class VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(VectorIndex.class);

    /** A chunk's searchable metadata plus its embedding. */
    public record Entry(
        long chunkId,
        KnowledgeSourceType sourceType,
        long sourceId,
        String title,
        Long projectId,
        Long ownerId,
        AccessScope accessScope,
        String category,
        java.time.LocalDateTime updatedAt,
        float[] vector
    ) {}

    /** A semantic candidate with its cosine similarity to the question. */
    public record Scored(Entry entry, float cosine) {}

    private final KnowledgeChunkRepository chunkRepository;
    private final PermissionService permissionService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int vectorMax;

    private final Map<Long, Entry> store = new ConcurrentHashMap<>();

    public VectorIndex(
            KnowledgeChunkRepository chunkRepository,
            PermissionService permissionService,
            @Value("${nexus.ai.retrieval.vector-max:5000}") int vectorMax) {
        this.chunkRepository = chunkRepository;
        this.permissionService = permissionService;
        this.vectorMax = vectorMax;
    }

    /** Warm the index once the application (and Flyway) is ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        refresh();
    }

    /** Periodic full reload so other instances' writes become visible here. */
    @Scheduled(
        fixedDelayString = "${nexus.ai.retrieval.refresh-ms:300000}",
        initialDelayString = "${nexus.ai.retrieval.refresh-ms:300000}")
    public void refresh() {
        try {
            List<KnowledgeChunk> chunks = chunkRepository.findEmbeddable(vectorMax);
            Map<Long, Entry> next = new ConcurrentHashMap<>();
            for (KnowledgeChunk chunk : chunks) {
                Entry entry = toEntry(chunk);
                if (entry != null) next.put(entry.chunkId(), entry);
            }
            store.clear();
            store.putAll(next);
            log.info("Vector index refreshed: {} embeddings", next.size());
        } catch (Exception e) {
            // Never fail the application over a cache refresh; text search still works.
            log.warn("Vector index refresh failed: {}", e.getMessage());
        }
    }

    /** Incrementally index one (re)indexed chunk. */
    public void upsert(KnowledgeChunk chunk) {
        if (chunk == null || chunk.getId() == null) return;
        try {
            Entry entry = toEntry(chunk);
            if (entry == null) store.remove(chunk.getId());
            else store.put(entry.chunkId(), entry);
        } catch (Exception e) {
            log.warn("Vector index upsert failed for chunk {}: {}", chunk.getId(), e.getMessage());
        }
    }

    /** Drop every vector belonging to a source record (called before re-insert). */
    public void removeBySource(KnowledgeSourceType sourceType, Long sourceId) {
        store.entrySet().removeIf(e ->
            e.getValue().sourceType() == sourceType && e.getValue().sourceId() == sourceId);
    }

    public void clear() {
        store.clear();
    }

    public int size() {
        return store.size();
    }

    /**
     * Top semantic matches for a query vector, permission-filtered for the
     * given user and optional project scope.
     */
    public List<Scored> topSemantic(float[] query, int limit, User user, Long projectIdFilter) {
        if (query == null || query.length == 0 || user == null) return List.of();

        List<Scored> scored = new ArrayList<>();
        for (Entry entry : store.values()) {
            if (!permissionService.canRead(entry.accessScope(), entry.ownerId(), user)) continue;
            if (projectIdFilter != null && entry.projectId() != null
                    && !entry.projectId().equals(projectIdFilter)) continue;
            float cosine = cosine(query, entry.vector());
            scored.add(new Scored(entry, cosine));
        }
        scored.sort(Comparator.comparingDouble(Scored::cosine).reversed());
        return scored.size() > limit ? List.copyOf(scored.subList(0, limit)) : scored;
    }

    /** Cosine similarity of two equally sized vectors; 0 when not comparable. */
    static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0f;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na == 0 || nb == 0) return 0f;
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    private Entry toEntry(KnowledgeChunk chunk) {
        if (chunk.getEmbedding() == null || chunk.getEmbedding().isBlank()) return null;
        try {
            float[] vector = mapper.readValue(chunk.getEmbedding(), float[].class);
            if (vector.length == 0) return null;
            return new Entry(
                chunk.getId(),
                chunk.getSourceType(),
                chunk.getSourceId(),
                chunk.getTitle(),
                chunk.getProjectId(),
                chunk.getOwnerId(),
                chunk.getAccessScope(),
                chunk.getCategory(),
                chunk.getUpdatedAt(),
                vector);
        } catch (Exception e) {
            log.warn("Skipping unreadable embedding on chunk {}", chunk.getId());
            return null;
        }
    }
}
