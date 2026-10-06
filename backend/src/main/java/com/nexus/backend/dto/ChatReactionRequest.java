package com.nexus.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Add or remove a reaction on a message. */
public record ChatReactionRequest(
    @NotBlank(message = "Emoji is required")
    @Size(max = 16, message = "Emoji key must be at most 16 characters")
    String emoji,

    /** true to add the current user's reaction, false to remove it. */
    @NotNull(message = "Add/remove flag is required")
    Boolean add
) {}
