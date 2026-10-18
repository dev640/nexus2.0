package com.nexus.backend.web;

import com.nexus.backend.dto.NotificationResponse;
import com.nexus.backend.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> listMine() {
        return ResponseEntity.ok(notificationService.findMine());
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount() {
        return ResponseEntity.ok(Map.of("count", notificationService.countUnread()));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        notificationService.archive(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead() {
        notificationService.markAllRead();
        return ResponseEntity.noContent().build();
    }

    /**
     * Re-stamps the company on every notification row and push subscription that
     * already belongs to this account. Used after an account's company changes so
     * both the inbox and the device list continue to answer for the current company
     * rather than the one they were filed under.
     *
     * <p>Read-only on the controller, write inside the service: the controller does
     * not need the company id, and the service's tenant context is not relevant here
     * — this is a per-account cleanup, not a per-tenant query.
     */
    @PostMapping("/own-company")
    public ResponseEntity<Void> reStampCompany() {
        notificationService.reStampCompanyOnNotifications();
        notificationService.reStampCompanyOnPushDevices();
        return ResponseEntity.noContent().build();
    }
}
