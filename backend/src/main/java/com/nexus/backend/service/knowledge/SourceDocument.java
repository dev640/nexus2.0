package com.nexus.backend.service.knowledge;

import com.nexus.backend.domain.knowledge.AccessScope;
import com.nexus.backend.domain.knowledge.KnowledgeSourceType;

import java.time.LocalDateTime;

/**
 * A normalized document produced from a Nexus record, ready to be chunked and
 * indexed. Carries the metadata every indexed item must retain.
 *
 * <p>{@code companyId} is the company that owns the source record. It is
 * carried rather than inferred from the ambient tenant because the two legitimately
 * differ: a reconcile pass walks every company's records at once, and a chunk that
 * took its company from whoever happened to be signed in would be filed under the
 * wrong one — silently, since the row would still be valid.
 */
public record SourceDocument(
    KnowledgeSourceType type,
    long sourceId,
    String title,
    String content,
    String category,
    Long projectId,
    String tags,
    AccessScope accessScope,
    Long ownerId,
    LocalDateTime sourceUpdatedAt,
    Long companyId
) {}
