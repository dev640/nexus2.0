package com.nexus.backend.web;

import com.nexus.backend.dto.MessageBroadcastRequest;
import com.nexus.backend.dto.MessageRequest;
import com.nexus.backend.dto.MessageResponse;
import com.nexus.backend.service.MessageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Internal mail. Deliberately not guarded by @WorkspaceWrite: writing to a
 * colleague is personal correspondence, like reading your own inbox, so a
 * read-only VIEWER account may send and receive too.
 */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping
    public ResponseEntity<List<MessageResponse>> inbox() {
        return ResponseEntity.ok(messageService.inbox());
    }

    @GetMapping("/sent")
    public ResponseEntity<List<MessageResponse>> sent() {
        return ResponseEntity.ok(messageService.sent());
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount() {
        return ResponseEntity.ok(Map.of("count", messageService.countUnread()));
    }

    @PostMapping
    public ResponseEntity<MessageResponse> send(@Valid @RequestBody MessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(messageService.send(request));
    }

    /**
     * Writes to everyone at once. Returns the copy that lands in the caller's
     * Sent box; each recipient sees the same letter in their own inbox.
     */
    @PostMapping("/broadcast")
    public ResponseEntity<MessageResponse> broadcast(@Valid @RequestBody MessageBroadcastRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(messageService.broadcast(request));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id) {
        messageService.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        messageService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
