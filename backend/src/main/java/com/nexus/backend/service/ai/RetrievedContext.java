package com.nexus.backend.service.ai;

import com.nexus.backend.domain.knowledge.KnowledgeSourceType;

import java.time.LocalDateTime;

/**
 * One retrieved slice of Nexus knowledge, ready to be packed into a prompt.
 * Scored during retrieval; content is pre-truncated to the context budget.
 */
public record RetrievedContext(
    KnowledgeSourceType sourceType,
    Long sourceId,
    String title,
    String content,
    String category,
    Long projectId,
    LocalDateTime updatedAt,
    double score
) {}
