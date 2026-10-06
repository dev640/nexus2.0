package com.nexus.backend.service.knowledge;

import com.nexus.backend.domain.knowledge.AccessScope;
import com.nexus.backend.domain.knowledge.KnowledgeSourceType;

import java.time.LocalDateTime;

/**
 * A normalized document produced from a Nexus record, ready to be chunked and
 * indexed. Carries the metadata every indexed item must retain.
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
    LocalDateTime sourceUpdatedAt
) {}
