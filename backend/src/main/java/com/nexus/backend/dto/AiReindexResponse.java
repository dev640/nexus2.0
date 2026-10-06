package com.nexus.backend.dto;

/** Result of an incremental knowledge re-index run. */
public record AiReindexResponse(
    int sources,
    int reindexed,
    int removed,
    int vectors
) {}
