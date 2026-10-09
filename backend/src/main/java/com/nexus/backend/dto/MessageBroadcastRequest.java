package com.nexus.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A letter to the whole workspace. Same fields as {@link MessageRequest} minus
 * the recipient: the audience is everyone, and the sender's own inbox is left
 * out — there is no reply to wait for from yourself.
 */
public record MessageBroadcastRequest(
    @NotBlank(message = "Subject is required")
    @Size(max = 200, message = "Subject must not exceed 200 characters")
    String subject,

    @NotBlank(message = "Message is required")
    @Size(max = 5000, message = "Message must not exceed 5000 characters")
    String body
) {}
