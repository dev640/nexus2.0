package com.nexus.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Ask request: a question plus optional conversation and project scope. */
public record AiAskRequest(
    @NotBlank(message = "Question is required")
    @Size(max = 2000, message = "Question must not exceed 2000 characters")
    String question,
    UUID conversationId,
    Long projectId
) {}
