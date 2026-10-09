package com.nexus.backend.service.activity;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.service.NotificationService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns workspace activity into notifications, for everyone the activity
 * concerns except the person who caused it.
 *
 * <p>The event is published from inside the changing service's transaction and
 * handled synchronously, so the notification is committed with the change it
 * describes and pushed to live sockets in the same pass.
 */
@Component
public class WorkspaceActivityListener {

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    public WorkspaceActivityListener(NotificationService notificationService, UserRepository userRepository) {
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    @EventListener
    public void onActivity(WorkspaceActivity activity) {
        List<User> everyone = userRepository.findAll();
        if (everyone.isEmpty()) return;

        String actor = activity.actorEmail();
        String actorName = actor == null ? null : everyone.stream()
            .filter(user -> actor.equalsIgnoreCase(user.getEmail()))
            .map(User::getName)
            .findFirst()
            .orElse(null);

        List<User> audience = everyone.stream()
            .filter(user -> activity.recipientIds() == null || activity.recipientIds().contains(user.getId()))
            .filter(user -> actor == null || !actor.equalsIgnoreCase(user.getEmail()))
            .filter(user -> activity.excludeIds() == null || !activity.excludeIds().contains(user.getId()))
            .toList();
        if (audience.isEmpty()) return;

        // "Someone" when the actor is unknown (a system action, or an account
        // deleted mid-request), so the sentence still reads.
        String text = (actorName != null ? actorName : "Someone") + " " + activity.actionText();
        notificationService.notifyUsers(audience, activity.category(), text, activity.link());
    }
}
