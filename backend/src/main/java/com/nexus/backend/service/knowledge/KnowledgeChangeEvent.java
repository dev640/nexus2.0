package com.nexus.backend.service.knowledge;

import com.nexus.backend.domain.knowledge.KnowledgeSourceType;

/** Published after a source record is created or updated. */
public record KnowledgeChangeEvent(KnowledgeSourceType sourceType, Long sourceId) {}
