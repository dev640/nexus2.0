package com.nexus.backend.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** One persisted chat message; assistant messages carry their citations. */
public record AiMessageResponse(
    UUID id,
    String role,
    String content,
    List<AiSourceResponse> sources,
    String mode,
    String model,
    LocalDateTime createdAt
) {}
