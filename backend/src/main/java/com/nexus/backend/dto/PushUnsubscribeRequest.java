package com.nexus.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A browser asking to stop receiving notifications.
 *
 * <p>The endpoint is the identity of the device, so it is the whole request. It
 * is not secret — it was issued to that browser — but it is only honoured for
 * the account that owns the row.
 */
public record PushUnsubscribeRequest(
    @NotBlank(message = "A push endpoint is required")
    @Size(max = 1000, message = "That push endpoint is too long")
    String endpoint
) {}
