package com.nexus.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A browser registering itself for push notifications.
 *
 * <p>The shape is the browser's, not ours: `PushSubscription.toJSON()` produces
 * exactly these three fields, so nothing has to be translated on the way in. The
 * ceilings are the columns' — a push endpoint is long (Google's run to several
 * hundred characters) and the keys are around 88 and 22.
 */
public record PushSubscriptionRequest(
    @NotBlank(message = "A push endpoint is required")
    @Size(max = 1000, message = "That push endpoint is too long")
    String endpoint,

    @NotNull(message = "The subscription keys are required")
    @Valid
    Keys keys
) {
    public record Keys(
        @NotBlank(message = "The browser's public key is required")
        @Size(max = 300, message = "That key is too long")
        String p256dh,

        @NotBlank(message = "The auth secret is required")
        @Size(max = 300, message = "That secret is too long")
        String auth
    ) {}
}
