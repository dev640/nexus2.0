package com.nexus.backend.service;

import com.nexus.backend.domain.notification.Notification;
import com.nexus.backend.domain.task.Task;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.dto.NotificationResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.NotificationRepository;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.service.push.PushSubscriptionService;
import com.nexus.backend.service.push.WebPushSender;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class NotificationService {

    /** The column's ceiling; longer text is cut rather than rejected. */
    private static final int TEXT_LIMIT = 500;

    /** Longer than any route the app serves; anything beyond it is dropped. */
    private static final int LINK_LIMIT = 300;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ChatSocketFacade socketFacade;
    private final WebPushSender webPushSender;
    private final PushSubscriptionService pushSubscriptionService;

    public NotificationService(
        NotificationRepository notificationRepository,
        UserRepository userRepository,
        ChatSocketFacade socketFacade,
        WebPushSender webPushSender,
        PushSubscriptionService pushSubscriptionService
    ) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.socketFacade = socketFacade;
        this.webPushSender = webPushSender;
        this.pushSubscriptionService = pushSubscriptionService;
    }

    /**
     * Writes the same text to each recipient and pushes it to whoever is
     * connected. Saving and pushing happen together on purpose: a notification
     * that reaches the socket but not the database would vanish on reload, and
     * one that is stored but never pushed would sit unseen until a page load.
     *
     * <p>A recipient with no live socket also gets a Web Push, which is the only
     * way to reach a phone whose app is closed. Recipients who *are* connected do
     * not: their open tab alerts itself the moment the frame arrives, and pushing
     * as well would announce the same thing twice on the same device.
     */
    @Transactional
    public void notifyUsers(List<User> recipients, Notification.Category category, String text, String link) {
        if (recipients == null || recipients.isEmpty()) return;
        String message = trim(text);
        String target = appRelativeLink(link);
        // Read once per company: presence is a workspace fact, and a recipient's
        // live socket is found by looking in their own company, not in all of them.
        Map<Long, Set<Long>> connectedByCompany = new HashMap<>();
        for (User recipient : recipients) {
            if (recipient == null || recipient.getId() == null) continue;
            Notification n = new Notification();
            n.setRecipient(recipient);
            n.setCategory(category);
            n.setText(message);
            n.setLink(target);
            Notification saved = notificationRepository.save(n);
            socketFacade.pushNotification(recipient.getId(), mapToResponse(saved));
            Set<Long> connected = connectedByCompany.computeIfAbsent(recipient.getCompanyId(),
                company -> company == null ? Set.of() : new HashSet<>(socketFacade.onlineUserIds(company)));
            if (!connected.contains(recipient.getId())) {
                webPushSender.sendToUserInBackground(
                    recipient.getId(), recipient.getCompanyId(), pushPayload(saved));
            }
            saved.setCompanyId(recipient.getCompanyId());
        }
    }

    /**
     * The notification as a push payload.
     *
     * <p>Shaped like the popup the socket path shows — same title, same sentence —
     * so an alert reads the same whether it arrived on an open laptop or a closed
     * phone. The tag is the notification's id, which lets a re-sent alert replace
     * itself instead of stacking.
     */
    private static Map<String, Object> pushPayload(Notification notification) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "Nexus");
        payload.put("body", notification.getText());
        payload.put("url", notification.getLink() == null ? "/" : notification.getLink());
        payload.put("tag", "nexus-notification-" + notification.getId());
        return payload;
    }

    /** Generate a notification when a task is assigned to someone. */
    @Transactional
    public void notifyTaskAssigned(Task task, User assignee) {
        if (assignee == null || task == null) return;
        String actor = currentUserEmail();
        if (actor != null && actor.equals(assignee.getEmail())) return; // no self-notifications

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

    /**
     * Re-stamps the company on every notification row that already belongs to this
     * account, so a change of the account's company does not leave the inbox
     * answering for the previous tenant.
     */
    @Transactional
    public void reStampCompanyOnNotifications() {
        User me = currentUser();
        Long companyId = me.getCompanyId();
        if (companyId == null) {
            throw new ValidationException(
                "There is nobody else in the workspace to write to");
        }
        List<Notification> mine = notificationRepository.findByRecipientOrderByCreatedAtDesc(me);
        for (Notification n : mine) {
            n.setCompanyId(companyId);
        }
        notificationRepository.saveAll(mine);
    }

    /**
     * Re-stamps the company on every push subscription that already belongs to this
     * account, so a change of the account's company does not leave the device list
     * answering for the previous tenant.
     */
    @Transactional
    public void reStampCompanyOnPushDevices() {
        User me = currentUser();
        Long companyId = me.getCompanyId();
        if (companyId == null) {
            throw new ValidationException(
                "There is nobody else in the workspace to write to");
        }
        pushSubscriptionService.restampCompanyOnDevices(me);
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
