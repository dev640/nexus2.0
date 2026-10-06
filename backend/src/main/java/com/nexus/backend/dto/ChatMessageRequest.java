package com.nexus.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatMessageRequest(
    @NotBlank(message = "Message body is required")
    @Size(max = 4000, message = "Message must be at most 4000 characters")
    String body
) {}
