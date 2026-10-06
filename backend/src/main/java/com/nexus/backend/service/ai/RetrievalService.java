package com.nexus.backend.service.ai;

import com.nexus.backend.domain.knowledge.KnowledgeChunk;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.repository.KnowledgeChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Permission-aware hybrid retrieval: full-text search (GIN indexed), keyword
 * match, and semantic ranking from the in-memory vector index, fused with
 * reciprocal rank fusion. Returns the smallest set of chunks that can answer
 * the question, within a fixed character budget — the database is never dumped
 * into a prompt.
 *
 * Text candidates are already scope-filtered in SQL; semantic candidates are
 * filtered again by {@link VectorIndex}/{@link PermissionService} in-process,
 * so a user can never retrieve a chunk they are not allowed to read.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** Reciprocal-rank-fusion damping: larger = flatter contribution. */
    static final int RRF_K = 60;

    /** Generic filler words excluded from the keyword signal. */
    private static final Set<String> STOPWORDS = Set.of(
        "the", "and", "for", "are", "but", "not", "you", "all", "any", "can",
        "her", "was", "one", "our", "out", "who", "how", "what", "when", "why",
        "where", "this", "that", "these", "those", "with", "from", "about",
        "into", "over", "under", "been", "have", "has", "had", "does", "did",
        "will", "would", "could", "should", "may", "might", "must", "shall",
        "than", "then", "there", "their", "them", "they", "were", "which",
        "give", "get", "show", "tell", "some", "more", "most", "other");

    private final KnowledgeChunkRepository chunkRepository;
    private final VectorIndex vectorIndex;
    private final AiEngine engine;
    private final PermissionService permissionService;

    private final int ftsCandidates;
    private final int keywordCandidates;
    private final int maxChunks;
    private final double minCosine;

    public RetrievalService(
            KnowledgeChunkRepository chunkRepository,
            VectorIndex vectorIndex,
            AiEngine engine,
            PermissionService permissionService,
            @Value("${nexus.ai.retrieval.candidates:80}") int ftsCandidates,
            @Value("${nexus.ai.retrieval.keyword-candidates:60}") int keywordCandidates,
            @Value("${nexus.ai.retrieval.max-chunks:6}") int maxChunks,
            @Value("${nexus.ai.retrieval.min-cosine:0.08}") double minCosine) {
        this.chunkRepository = chunkRepository;
        this.vectorIndex = vectorIndex;
        this.engine = engine;
        this.permissionService = permissionService;
        this.ftsCandidates = ftsCandidates;
        this.keywordCandidates = keywordCandidates;
        this.maxChunks = maxChunks;
        this.minCosine = minCosine;
    }

    /**
     * Retrieve the most relevant, authorized chunks for a question.
     *
     * @param question the user's question
     * @param projectId optional project scope (null = whole workspace)
     * @param user the authenticated user whose permissions apply
     */
    public List<RetrievedContext> retrieve(String question, Long projectId, User user) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty() || !q.matches(".*[A-Za-z0-9].*") || user == null) return List.of();

        Long userId = user.getId();
        boolean isAdmin = permissionService.isAdmin(user);
        Map<Long, Candidate> candidates = new LinkedHashMap<>();

        // 1) Full-text candidates (permission-filtered in SQL).
        List<KnowledgeChunk> fts = chunkRepository.searchFullText(q, userId, isAdmin, projectId, ftsCandidates);
        for (int i = 0; i < fts.size(); i++) {
            candidate(candidates, fts.get(i).getId()).accept(fts.get(i), i + 1, null, null, 0f);
        }

        // 2) Keyword candidates from sanitized question tokens.
        String pattern = keywordPattern(q);
        if (pattern != null) {
            List<KnowledgeChunk> keyword = chunkRepository.searchKeyword(
                pattern, userId, isAdmin, projectId, keywordCandidates);
            for (int i = 0; i < keyword.size(); i++) {
                candidate(candidates, keyword.get(i).getId()).accept(keyword.get(i), null, i + 1, null, 0f);
            }
        }

        // 3) Semantic candidates from the in-memory vector index.
        float[] queryVector = embedQuery(q);
        if (queryVector != null) {
            List<VectorIndex.Scored> semantic = vectorIndex.topSemantic(
                queryVector, keywordCandidates, user, projectId);
            for (int i = 0; i < semantic.size(); i++) {
                VectorIndex.Scored scored = semantic.get(i);
                candidate(candidates, scored.entry().chunkId())
                    .accept(null, null, null, i + 1, scored.cosine());
            }
        }

        // 4) Reciprocal-rank fusion; drop purely semantic hits that are weak.
        List<Candidate> ordered = new ArrayList<>();
        for (Candidate c : candidates.values()) {
            boolean textMatch = c.ftsRank >= 0 || c.kwRank >= 0;
            boolean weakSemanticOnly = !textMatch && c.cosine < minCosine;
            if (weakSemanticOnly) continue;
            double score = 0;
            if (c.ftsRank >= 0) score += 1.0 / (RRF_K + c.ftsRank);
            if (c.kwRank >= 0) score += 1.0 / (RRF_K + c.kwRank);
            if (c.semRank >= 0) score += 1.0 / (RRF_K + c.semRank);
            if (score <= 0) continue;
            c.score = score;
            ordered.add(c);
        }
        ordered.sort((a, b) -> Double.compare(b.score, a.score));

        // 5) Load rows the text queries did not return (semantic-only hits).
        loadMissing(ordered);

        // 6) Smallest useful context package: top chunks, at most two per
        //    source, within the character budget.
        List<RetrievedContext> out = new ArrayList<>();
        Map<String, Integer> perSource = new HashMap<>();
        int budget = PromptBuilder.MAX_CONTEXT_CHARS;
        for (Candidate c : ordered) {
            if (out.size() >= maxChunks) break;
            KnowledgeChunk chunk = c.chunk;
            if (chunk == null) continue;
            String key = chunk.getSourceType() + ":" + chunk.getSourceId();
            if (perSource.merge(key, 1, Integer::sum) > 2) continue;
            String content = PromptBuilder.truncate(chunk.getContent(), PromptBuilder.MAX_CHUNK_CHARS);
            if (content.length() + 120 > budget) continue;
            budget -= content.length();
            out.add(new RetrievedContext(
                chunk.getSourceType(),
                chunk.getSourceId(),
                chunk.getTitle(),
                content,
                chunk.getCategory(),
                chunk.getProjectId(),
                chunk.getSourceUpdatedAt() != null ? chunk.getSourceUpdatedAt() : chunk.getUpdatedAt(),
                c.score));
        }

        log.debug("Retrieved {} chunks for question ({} candidates)", out.size(), ordered.size());
        return out;
    }

    // ---------- internals ----------

    /** Mutable per-chunk candidate with its rank in each result list. */
    private static final class Candidate {
        KnowledgeChunk chunk;
        long chunkId;
        int ftsRank = -1;
        int kwRank = -1;
        int semRank = -1;
        float cosine = Float.NaN;
        double score;

        void accept(KnowledgeChunk chunk, Integer ftsRank, Integer kwRank, Integer semRank, Float cosine) {
            if (chunk != null) this.chunk = chunk;
            if (ftsRank != null) this.ftsRank = ftsRank;
            if (kwRank != null) this.kwRank = kwRank;
            if (semRank != null) this.semRank = semRank;
            if (cosine != null) this.cosine = cosine;
        }
    }

    private Candidate candidate(Map<Long, Candidate> candidates, long chunkId) {
        return candidates.computeIfAbsent(chunkId, id -> {
            Candidate c = new Candidate();
            c.chunkId = id;
            return c;
        });
    }

    private void loadMissing(List<Candidate> ordered) {
        Set<Long> missing = new LinkedHashSet<>();
        for (Candidate c : ordered) {
            if (c.chunk == null) missing.add(c.chunkId);
        }
        if (missing.isEmpty()) return;
        Map<Long, KnowledgeChunk> found = chunkRepository.findAllById(missing).stream()
            .collect(Collectors.toMap(KnowledgeChunk::getId, Function.identity(), (a, b) -> a));
        for (Candidate c : ordered) {
            if (c.chunk == null) c.chunk = found.get(c.chunkId);
        }
    }

    /** Question embedding for semantic ranking; null when unavailable. */
    private float[] embedQuery(String question) {
        if (!engine.isConfigured()) return null;
        try {
            List<float[]> vectors = engine.embed(List.of(question));
            return vectors.isEmpty() ? null : vectors.get(0);
        } catch (Exception e) {
            // Degrade to text search rather than failing the question.
            log.debug("Query embedding unavailable, using text search only: {}", e.getMessage());
            return null;
        }
    }

    /** Lowercase alphanumeric alternation like {@code (alpha|project|2026)}; null when nothing useful. */
    static String keywordPattern(String question) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : question.toLowerCase().split("[^a-z0-9]+")) {
            if (raw.length() < 3 || raw.length() > 30) continue;
            if (STOPWORDS.contains(raw)) continue;
            tokens.add(raw);
            if (tokens.size() >= 8) break;
        }
        if (tokens.isEmpty()) return null;
        return "(" + String.join("|", tokens) + ")";
    }
}
