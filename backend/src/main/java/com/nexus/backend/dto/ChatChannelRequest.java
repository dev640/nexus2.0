package com.nexus.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create a channel, or open/reuse a DM by naming the partner. */
public record ChatChannelRequest(
    @NotBlank(message = "Name is required")
    @Size(max = 80, message = "Name must be at most 80 characters")
    String name,

    /** PUBLIC (default) or PRIVATE. Ignored when partnerId is set. */
    String type,

    @Size(max = 255, message = "Topic must be at most 255 characters")
    String topic,

    /** When set, creates or returns the DM conversation with this user. */
    Long partnerId
) {}
