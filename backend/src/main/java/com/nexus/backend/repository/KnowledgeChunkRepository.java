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

    /**
     * Drops one source record's chunks within one company.
     *
     * <p>Company-scoped even though {@code (source_type, source_id)} already
     * identifies a single row: the delete is the destructive half of a re-index, and
     * a where-clause that cannot reach another company's rows is worth more than the
     * argument that it did not need to be there.
     */
    @Modifying
    @Query(value = """
        DELETE FROM knowledge_chunks
        WHERE source_type = :type AND source_id = :sourceId AND company_id = :companyId
        """, nativeQuery = true)
    int deleteBySourceTypeAndSourceIdAndCompanyId(@Param("type") String type,
                                                  @Param("sourceId") Long sourceId,
                                                  @Param("companyId") Long companyId);

    /**
     * Permission-filtered full-text search over the generated tsvector (GIN
     * indexed). The scope clause is evaluated in SQL so unauthorized rows never
     * leave the database; the in-memory vector index applies the same filter.
     */
    @Query(value = """
        SELECT * FROM knowledge_chunks c
        WHERE c.company_id = :companyId
        AND (
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
        @Param("companyId") Long companyId,
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
        WHERE c.company_id = :companyId
        AND (
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
        @Param("companyId") Long companyId,
        @Param("userId") Long userId,
        @Param("isAdmin") boolean isAdmin,
        @Param("projectId") Long projectId,
        @Param("limit") int limit);

    /**
     * Most recently updated chunks that carry an embedding, for vector warm-up.
     *
     * <p>Deliberately not company-filtered: it fills one in-memory index shared by
     * every company, and each entry remembers whose it is, so isolation is applied
     * when a question is asked rather than when the cache is warmed. Filtering here
     * would mean a separate query per company on every refresh.
     */
    @Query(value = """
        SELECT * FROM knowledge_chunks
        WHERE embedding IS NOT NULL
        ORDER BY updated_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<KnowledgeChunk> findEmbeddable(@Param("limit") int limit);

    /**
     * Per-source latest source timestamp within one company, used to re-index only
     * stale records. Scoped so a reconcile compares against, and later prunes, only
     * the chunks it is allowed to touch.
     */
    @Query(value = """
        SELECT source_type, source_id, MAX(source_updated_at)
        FROM knowledge_chunks
        WHERE company_id = :companyId
        GROUP BY source_type, source_id
        """, nativeQuery = true)
    List<Object[]> findSourceTimestamps(@Param("companyId") Long companyId);

    /**
     * Drops one company's whole index, for a full rebuild of that company only.
     *
     * <p>Not {@code deleteAll()}: that would let one company's maintenance action
     * empty every other company's index.
     */
    @Modifying
    @Query("delete from KnowledgeChunk c where c.companyId = :companyId")
    int deleteByCompanyId(@Param("companyId") Long companyId);

    long countBySourceTypeAndCompanyId(KnowledgeSourceType sourceType, Long companyId);

    long countBySourceTypeAndEmbeddingIsNotNullAndCompanyId(KnowledgeSourceType sourceType, Long companyId);

    long countByEmbeddingIsNotNullAndCompanyId(Long companyId);

    long countByCompanyId(Long companyId);
}
