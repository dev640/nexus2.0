package com.nexus.backend.service.activity;

import com.nexus.backend.domain.notification.Notification;

import java.util.List;

/**
 * Something worth telling people about happened in the workspace.
 *
 * <p>The action text is phrased without the actor ("created the project
 * \"Nimbus\"") because the listener prefixes the actor's name, and the publisher
 * does not always know it — only the security context does.
 *
 * @param link         app-relative path to what the activity happened on, so the
 *                     alert can open it; null when there is nothing to open
 * @param recipientIds null means the whole workspace; otherwise only these users
 * @param excludeIds   users to leave out on top of the actor, e.g. the assignee
 *                     of a task who is about to get a more specific notification
 */
public record WorkspaceActivity(
    Notification.Category category,
    String actionText,
    String link,
    String actorEmail,
    List<Long> recipientIds,
    List<Long> excludeIds
) {}
