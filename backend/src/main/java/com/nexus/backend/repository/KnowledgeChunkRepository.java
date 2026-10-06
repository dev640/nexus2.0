package com.nexus.backend.repository;

import com.nexus.backend.domain.knowledge.KnowledgeChunk;
import com.nexus.backend.domain.knowledge.KnowledgeSourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findBySourceTypeAndSourceIdOrderByChunkIndex(
        KnowledgeSourceType sourceType, Long sourceId);

    @Modifying
    @Query(value = """
        DELETE FROM knowledge_chunks WHERE source_type = :type AND source_id = :sourceId
        """, nativeQuery = true)
    int deleteBySourceTypeAndSourceId(@Param("type") String type, @Param("sourceId") Long sourceId);

    /**
     * Permission-filtered full-text search over the generated tsvector (GIN
     * indexed). The scope clause is evaluated in SQL so unauthorized rows never
     * leave the database; the in-memory vector index applies the same filter.
     */
    @Query(value = """
        SELECT * FROM knowledge_chunks c
        WHERE (
            c.access_scope = 'WORKSPACE'
            OR (c.access_scope = 'PRIVATE' AND c.owner_id = :userId)
            OR (c.access_scope = 'ADMIN' AND :isAdmin = true)
        )
        AND (cast(:projectId as bigint) IS NULL OR c.project_id IS NULL OR c.project_id = :projectId)
        AND c.search_vector @@ websearch_to_tsquery('english', :query)
        ORDER BY ts_rank(c.search_vector, websearch_to_tsquery('english', :query)) DESC,
                 c.updated_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<KnowledgeChunk> searchFullText(
        @Param("query") String query,
        @Param("userId") Long userId,
        @Param("isAdmin") boolean isAdmin,
        @Param("projectId") Long projectId,
        @Param("limit") int limit);

    /**
     * Permission-filtered keyword search: {@code pattern} is a lowercase
     * alternation such as {@code (alpha|project)} built in the service from
     * sanitized question tokens. Complements full-text search for short or
     * partial terms.
     */
    @Query(value = """
        SELECT * FROM knowledge_chunks c
        WHERE (
            c.access_scope = 'WORKSPACE'
            OR (c.access_scope = 'PRIVATE' AND c.owner_id = :userId)
            OR (c.access_scope = 'ADMIN' AND :isAdmin = true)
        )
        AND (cast(:projectId as bigint) IS NULL OR c.project_id IS NULL OR c.project_id = :projectId)
        AND (lower(c.title) ~ :pattern OR lower(c.content) ~ :pattern)
        ORDER BY c.updated_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<KnowledgeChunk> searchKeyword(
        @Param("pattern") String pattern,
        @Param("userId") Long userId,
        @Param("isAdmin") boolean isAdmin,
        @Param("projectId") Long projectId,
        @Param("limit") int limit);

    /** Most recently updated chunks that carry an embedding, for vector warm-up. */
    @Query(value = """
        SELECT * FROM knowledge_chunks
        WHERE embedding IS NOT NULL
        ORDER BY updated_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<KnowledgeChunk> findEmbeddable(@Param("limit") int limit);

    /** Per-source latest source timestamp, used to re-index only stale records. */
    @Query(value = """
        SELECT source_type, source_id, MAX(source_updated_at)
        FROM knowledge_chunks
        GROUP BY source_type, source_id
        """, nativeQuery = true)
    List<Object[]> findSourceTimestamps();

    long countBySourceType(KnowledgeSourceType sourceType);

    long countBySourceTypeAndEmbeddingIsNotNull(KnowledgeSourceType sourceType);

    long countByEmbeddingIsNotNull();
}
