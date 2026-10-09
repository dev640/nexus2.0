package com.nexus.backend.service.activity;

import com.nexus.backend.domain.notification.Notification;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * Null-safe publishers for workspace activity, following the same shape as
 * KnowledgeEvents: services already hold an ApplicationEventPublisher, so
 * announcing something costs one line and no new dependency. Unit tests that
 * construct a service directly pass no publisher, which this tolerates.
 *
 * <p>The actor is read from the security context here rather than at every call
 * site, and the listener never notifies the person who caused the activity.
 *
 * <p>The link is the page the alert should open — usually the object the caller
 * just saved, so it carries the id it was saved with.
 */
public final class ActivityEvents {

    private ActivityEvents() {}

    /** Tells the whole workspace, minus the actor and any excluded users. */
    public static void workspace(
        ApplicationEventPublisher publisher,
        Notification.Category category,
        String actionText,
        String link,
        Long... excludeIds
    ) {
        if (publisher == null) return;
        publisher.publishEvent(new WorkspaceActivity(
            category,
            actionText,
            link,
            actorEmail(),
            null,
            excludeIds == null ? List.of() : List.of(excludeIds)
        ));
    }

    /** Tells specific people, e.g. the assignee of a task somebody else moved. */
    public static void toUsers(
        ApplicationEventPublisher publisher,
        Notification.Category category,
        String actionText,
        String link,
        List<Long> recipientIds
    ) {
        if (publisher == null || recipientIds == null || recipientIds.isEmpty()) return;
        publisher.publishEvent(new WorkspaceActivity(
            category,
            actionText,
            link,
            actorEmail(),
            recipientIds,
            List.of()
        ));
    }

    private static String actorEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }
}
