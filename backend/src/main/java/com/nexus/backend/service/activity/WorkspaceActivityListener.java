package com.nexus.backend.service.activity;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.service.NotificationService;
import com.nexus.backend.tenant.CurrentTenant;
import lombok.extern.slf4j.Slf4j;
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
 *
 * <p>The audience is the actor's company, never the whole account table. Both
 * halves of that matter: V19 pairs every notification with the company of its
 * recipient, so addressing one company's activity to another company's people
 * would be rejected by the database, and a notification that did reach them
 * would be a leak even if it had not been.
 */
@Slf4j
@Component
public class WorkspaceActivityListener {

    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final CurrentTenant currentTenant;

    public WorkspaceActivityListener(NotificationService notificationService, UserRepository userRepository,
                                     CurrentTenant currentTenant) {
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.currentTenant = currentTenant;
    }

    @EventListener
    public void onActivity(WorkspaceActivity activity) {
        Long companyId = activityCompany(activity);
        if (companyId == null) {
            // Nothing sensible to address the notification to, and guessing by
            // notifying everyone is exactly the cross-company mistake this
            // listener has to avoid.
            log.debug("Workspace activity from no company ignored: {}", activity.actionText());
            return;
        }

        List<User> everyone = userRepository.findByCompanyIdOrderByIdAsc(companyId);
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

    /**
     * The company the activity belongs to: the caller's when the event came from
     * a request (the normal case), and otherwise the actor's own, looked up from
     * the account that caused it.
     */
    private Long activityCompany(WorkspaceActivity activity) {
        Long tenant = currentTenant.orNull();
        if (tenant != null) {
            return tenant;
        }
        String actor = activity.actorEmail();
        if (actor == null) {
            return null;
        }
        return userRepository.findByEmail(actor).map(User::getCompanyId).orElse(null);
    }
}
