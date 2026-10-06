package com.nexus.backend.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.backend.domain.knowledge.KnowledgeChunk;
import com.nexus.backend.domain.knowledge.KnowledgeSourceType;
import com.nexus.backend.repository.KnowledgeChunkRepository;
import com.nexus.backend.service.ai.AiEngine;
import com.nexus.backend.service.ai.AiEngineException;
import com.nexus.backend.service.ai.VectorIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Knowledge/indexing service: keeps {@code knowledge_chunks} in sync with Nexus
 * data incrementally — a changed record re-indexes only its own chunks (never
 * the whole store), deletions drop its chunks, and {@link #reindex(boolean)}
 * reconciles stale and orphaned chunks on demand.
 */
@Service
public class KnowledgeIndexService {

    /** Result of a reconcile pass. */
    public record ReindexResult(int sources, int reindexed, int removed, int vectors) {}

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);

    private final KnowledgeChunkRepository chunkRepository;
    private final SourceDocuments sourceDocuments;
    private final Chunker chunker;
    private final AiEngine engine;
    private final VectorIndex vectorIndex;
    private final ObjectMapper mapper = new ObjectMapper();

    public KnowledgeIndexService(
            KnowledgeChunkRepository chunkRepository,
            SourceDocuments sourceDocuments,
            Chunker chunker,
            AiEngine engine,
            VectorIndex vectorIndex) {
        this.chunkRepository = chunkRepository;
        this.sourceDocuments = sourceDocuments;
        this.chunker = chunker;
        this.engine = engine;
        this.vectorIndex = vectorIndex;
    }

    /** Re-index one record: replace its chunks and update its vectors. */
    @Transactional
    public void sync(KnowledgeSourceType type, Long sourceId) {
        if (type == null || sourceId == null) return;

        SourceDocument document = sourceDocuments.find(type, sourceId).orElse(null);
        if (document == null) {
            remove(type, sourceId);
            return;
        }

        chunkRepository.deleteBySourceTypeAndSourceId(type.name(), sourceId);
        vectorIndex.removeBySource(type, sourceId);

        List<String> pieces = chunker.chunk(document.content());
        if (pieces.isEmpty()) pieces = List.of(""); // keep title-only records discoverable

        List<float[]> embeddings = embed(pieces);
        String embeddingModel = embeddings != null ? engine.embeddingModel() : null;

        for (int i = 0; i < pieces.size(); i++) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setSourceType(type);
            chunk.setSourceId(sourceId);
            chunk.setChunkIndex(i);
            chunk.setTitle(truncateTitle(document.title()));
            chunk.setContent(pieces.get(i));
            chunk.setCategory(document.category());
            chunk.setProjectId(document.projectId());
            chunk.setTags(document.tags());
            chunk.setAccessScope(document.accessScope());
            chunk.setOwnerId(document.ownerId());
            chunk.setSourceUpdatedAt(document.sourceUpdatedAt());
            if (embeddings != null && i < embeddings.size()) {
                String json = toJson(embeddings.get(i));
                if (json != null) {
                    chunk.setEmbedding(json);
                    chunk.setEmbeddingModel(embeddingModel);
                }
            }
            vectorIndex.upsert(chunkRepository.save(chunk));
        }
    }

    /** Drop a deleted record's chunks from the store and the vector index. */
    @Transactional
    public void remove(KnowledgeSourceType type, Long sourceId) {
        if (type == null || sourceId == null) return;
        chunkRepository.deleteBySourceTypeAndSourceId(type.name(), sourceId);
        vectorIndex.removeBySource(type, sourceId);
    }

    /**
     * Incremental reconcile: re-index only records whose source timestamp
     * differs from the index (or that are missing), delete chunks whose source
     * no longer exists, then refresh the vector index.
     *
     * @param full when true, rebuild the whole index from scratch first
     */
    @Transactional
    public ReindexResult reindex(boolean full) {
        if (full) {
            chunkRepository.deleteAll();
            vectorIndex.clear();
        }

        Map<String, java.time.LocalDateTime> indexed = sourceTimestamps();
        Set<String> seen = new HashSet<>();
        int reindexed = 0;

        for (SourceDocument document : sourceDocuments.all()) {
            String key = document.type().name() + ":" + document.sourceId();
            seen.add(key);
            java.time.LocalDateTime known = indexed.get(key);
            if (known == null || !known.equals(document.sourceUpdatedAt())) {
                sync(document.type(), document.sourceId());
                reindexed++;
            }
        }

        int removed = 0;
        for (String key : indexed.keySet()) {
            if (seen.contains(key)) continue;
            int split = key.lastIndexOf(':');
            try {
                KnowledgeSourceType type = KnowledgeSourceType.valueOf(key.substring(0, split));
                long id = Long.parseLong(key.substring(split + 1));
                remove(type, id);
                removed++;
            } catch (RuntimeException e) {
                log.warn("Could not prune orphaned index entry {}", key);
            }
        }

        vectorIndex.refresh();
        ReindexResult result = new ReindexResult(seen.size(), reindexed, removed, vectorIndex.size());
        log.info("Knowledge reindex complete: {} sources, {} re-indexed, {} pruned, {} vectors",
            result.sources(), result.reindexed(), result.removed(), result.vectors());
        return result;
    }

    /** Index statistics for the status endpoint. */
    public Map<String, Long> chunksBySource() {
        Map<String, Long> out = new HashMap<>();
        for (KnowledgeSourceType type : KnowledgeSourceType.values()) {
            out.put(type.name(), chunkRepository.countBySourceType(type));
        }
        return out;
    }

    public long totalChunks() {
        return chunkRepository.count();
    }

    public long embeddedChunks() {
        return chunkRepository.countByEmbeddingIsNotNull();
    }

    // ---------- internals ----------

    /** Embeds chunks when an engine is configured; null when degraded. */
    private List<float[]> embed(List<String> pieces) {
        if (!engine.isConfigured()) return null;
        try {
            return engine.embed(pieces);
        } catch (AiEngineException e) {
            log.warn("Embedding {} chunk(s) failed ({}); storing text without vectors",
                pieces.size(), e.userMessage());
            return null;
        } catch (Exception e) {
            log.warn("Embedding {} chunk(s) failed: {}", pieces.size(), e.getMessage());
            return null;
        }
    }

    private String toJson(float[] vector) {
        try {
            return mapper.writeValueAsString(vector);
        } catch (Exception e) {
            log.warn("Could not serialize embedding: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, java.time.LocalDateTime> sourceTimestamps() {
        Map<String, java.time.LocalDateTime> out = new HashMap<>();
        for (Object[] row : chunkRepository.findSourceTimestamps()) {
            String type = String.valueOf(row[0]);
            Object id = row[1];
            Object ts = row[2];
            if (ts == null) continue;
            java.time.LocalDateTime updated = ts instanceof Timestamp t
                ? t.toLocalDateTime()
                : ts instanceof java.time.LocalDateTime l ? l : null;
            if (updated != null) out.put(type + ":" + id, updated);
        }
        return out;
    }

    private static String truncateTitle(String title) {
        if (title == null) return "";
        return title.length() <= 300 ? title : title.substring(0, 300);
    }
}
