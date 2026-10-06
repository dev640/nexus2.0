package com.nexus.backend.dto;

/** A citation reference for an answer: one indexed Nexus record. */
public record AiSourceResponse(
    String type,
    Long sourceId,
    String title,
    String category,
    Long projectId,
    String updatedAt
) {}
