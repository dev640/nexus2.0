package com.nexus.backend.dto;

import java.time.LocalDateTime;

/**
 * One notification for the Inbox and for the alert that pops up when it lands.
 *
 * @param link app-relative path to what this notification is about (the task on
 *             the board, the wiki page, the chat message), or null when there is
 *             nothing to open; clients navigate straight to it on click
 */
public record NotificationResponse(
    Long id,
    String category,
    String text,
    String link,
    boolean read,
    LocalDateTime createdAt
) {}
