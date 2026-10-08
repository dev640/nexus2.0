package com.nexus.backend.dto;

import java.time.LocalDateTime;

/**
 * A message as one side of the conversation sees it. `senderName` and
 * `recipientName` are denormalised so the Inbox can render a row without a
 * second lookup per message.
 */
public record MessageResponse(
    Long id,
    Long senderId,
    String senderName,
    Long recipientId,
    String recipientName,
    String subject,
    String body,
    boolean read,
    LocalDateTime createdAt
) {}
