package com.nexus.backend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Summary of one chat thread owned by the current user. */
public record AiConversationResponse(
    UUID id,
    String title,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    long messageCount
) {}
