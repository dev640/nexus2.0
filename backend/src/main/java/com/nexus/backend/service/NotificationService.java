package com.nexus.backend.service;

import com.nexus.backend.domain.notification.Notification;
import com.nexus.backend.domain.task.Task;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.dto.NotificationResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.repository.NotificationRepository;
import com.nexus.backend.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NotificationService {

    /** The column's ceiling; longer text is cut rather than rejected. */
    private static final int TEXT_LIMIT = 500;

    /** Longer than any route the app serves; anything beyond it is dropped. */
    private static final int LINK_LIMIT = 300;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ChatSocketFacade socketFacade;

    public NotificationService(
        NotificationRepository notificationRepository,
        UserRepository userRepository,
        ChatSocketFacade socketFacade
    ) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.socketFacade = socketFacade;
    }

    /**
     * Writes the same text to each recipient and pushes it to whoever is
     * connected. Saving and pushing happen together on purpose: a notification
     * that reaches the socket but not the database would vanish on reload, and
     * one that is stored but never pushed would sit unseen until a page load.
     */
    @Transactional
    public void notifyUsers(List<User> recipients, Notification.Category category, String text, String link) {
        if (recipients == null || recipients.isEmpty()) return;
        String message = trim(text);
        String target = appRelativeLink(link);
        for (User recipient : recipients) {
            if (recipient == null || recipient.getId() == null) continue;
            Notification n = new Notification();
            n.setRecipient(recipient);
            n.setCategory(category);
            n.setText(message);
            n.setLink(target);
            Notification saved = notificationRepository.save(n);
            socketFacade.pushNotification(recipient.getId(), mapToResponse(saved));
        }
    }

    /** Generate a notification when a task is assigned to someone. */
    @Transactional
    public void notifyTaskAssigned(Task task, User assignee) {
        if (assignee == null || task == null) return;
        String actor = currentUserEmail();
        if (actor != null && actor.equals(assignee.getEmail())) return; // no self-notifications

        // A task that was never persisted has no page to open, so the alert is
        // text-only rather than pointing at "/board?task=null".
        String link = task.getId() != null ? "/board?task=" + task.getId() : null;
        notifyUsers(List.of(assignee), Notification.Category.TASKS,
            "You were assigned to \"" + task.getTitle() + "\"", link);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> findMine() {
        User me = currentUser();
        return notificationRepository.findByRecipientOrderByCreatedAtDesc(me).stream()
            .map(NotificationService::mapToResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public long countUnread() {
        return notificationRepository.countByRecipientAndReadFalse(currentUser());
    }

    @Transactional
    public void markRead(Long id) {
        Notification n = notificationRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Notification", "id", id));
        if (!n.getRecipient().getEmail().equals(currentUserEmail())) {
            throw new ResourceNotFoundException("Notification", "id", id);
        }
        n.setRead(true);
        notificationRepository.save(n);
    }

    @Transactional
    public void archive(Long id) {
        Notification n = notificationRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Notification", "id", id));
        if (!n.getRecipient().getEmail().equals(currentUserEmail())) {
            throw new ResourceNotFoundException("Notification", "id", id);
        }
        notificationRepository.delete(n);
    }

    @Transactional
    public void markAllRead() {
        User me = currentUser();
        List<Notification> unread = notificationRepository.findByRecipientOrderByCreatedAtDesc(me).stream()
            .filter(n -> !n.isRead())
            .toList();
        unread.forEach(n -> n.setRead(true));
        notificationRepository.saveAll(unread);
    }

    private User currentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
    }

    private String currentUserEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    private static String trim(String value) {
        if (value == null) return "";
        return value.length() <= TEXT_LIMIT ? value : value.substring(0, TEXT_LIMIT - 1) + "…";
    }

    /**
     * Keeps a link only if it is a path inside this app. A notification is a
     * place an alert sends the browser, and a stored absolute URL (or a
     * protocol-relative "//host") would turn a popup into an off-site jump.
     */
    private static String appRelativeLink(String link) {
        if (link == null) return null;
        String candidate = link.trim();
        if (candidate.isEmpty() || candidate.length() > LINK_LIMIT) return null;
        if (!candidate.startsWith("/") || candidate.startsWith("//")) return null;
        return candidate;
    }

    private static NotificationResponse mapToResponse(Notification n) {
        return new NotificationResponse(
            n.getId(),
            n.getCategory().name(),
            n.getText(),
            n.getLink(),
            n.isRead(),
            n.getCreatedAt()
        );
    }
}
