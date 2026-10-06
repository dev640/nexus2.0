package com.nexus.backend.service.knowledge;

import com.nexus.backend.domain.knowledge.KnowledgeSourceType;

/** Published after a source record is deleted. */
public record KnowledgeRemoveEvent(KnowledgeSourceType sourceType, Long sourceId) {}
