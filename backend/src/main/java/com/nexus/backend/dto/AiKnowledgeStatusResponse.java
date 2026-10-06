package com.nexus.backend.dto;

import java.util.Map;

/** Health of the AI engine and knowledge index, for the chat header/status. */
public record AiKnowledgeStatusResponse(
    boolean configured,
    String model,
    String embeddingModel,
    long indexedChunks,
    long embeddedChunks,
    int vectorsLoaded,
    Map<String, Long> bySource
) {}
