package com.nexus.backend.web;

import com.nexus.backend.dto.ChatChannelRequest;
import com.nexus.backend.dto.ChatChannelResponse;
import com.nexus.backend.dto.ChatMessageRequest;
import com.nexus.backend.dto.ChatMessageResponse;
import com.nexus.backend.dto.ChatReactionRequest;
import com.nexus.backend.dto.ChatUnreadResponse;
import com.nexus.backend.security.WorkspaceWrite;
import com.nexus.backend.service.ChatService;
import com.nexus.backend.service.ChatSocketFacade;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Slack-style chat: channels, direct messages, messages, reactions, unread
 * counts and search. Every route requires authentication; membership is
 * enforced inside {@link ChatService}.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatSocketFacade presence;

    public ChatController(ChatService chatService, ChatSocketFacade presence) {
        this.chatService = chatService;
        this.presence = presence;
    }

    // ---------- channels ----------

    @GetMapping("/channels")
    public ResponseEntity<List<ChatChannelResponse>> myChannels() {
        return ResponseEntity.ok(chatService.myChannels());
    }

    @GetMapping("/channels/discover")
    public ResponseEntity<List<ChatChannelResponse>> discoverChannels() {
        return ResponseEntity.ok(chatService.discoverChannels());
    }

    @WorkspaceWrite
    @PostMapping("/channels")
    public ResponseEntity<ChatChannelResponse> createChannel(@Valid @RequestBody ChatChannelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(chatService.createChannel(request));
    }

    @WorkspaceWrite
    @PostMapping("/channels/dm/{userId}")
    public ResponseEntity<ChatChannelResponse> openDirectMessage(@PathVariable Long userId) {
        return ResponseEntity.ok(chatService.openDirectMessage(userId));
    }

    @WorkspaceWrite
    @DeleteMapping("/channels/{id}")
    public ResponseEntity<Void> deleteChannel(@PathVariable Long id) {
        chatService.deleteChannel(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/channels/{id}/join")
    public ResponseEntity<ChatChannelResponse> joinChannel(@PathVariable Long id) {
        return ResponseEntity.ok(chatService.joinChannel(id));
    }

    @PostMapping("/channels/{id}/leave")
    public ResponseEntity<Void> leaveChannel(@PathVariable Long id) {
        chatService.leaveChannel(id);
        return ResponseEntity.noContent().build();
    }

    @WorkspaceWrite
    @PatchMapping("/channels/{id}/topic")
    public ResponseEntity<ChatChannelResponse> updateTopic(
        @PathVariable Long id,
        @Valid @RequestBody UpdateTopicRequest request
    ) {
        return ResponseEntity.ok(chatService.updateTopic(id, request.topic()));
    }

    // ---------- messages ----------

    @GetMapping("/channels/{id}/messages")
    public ResponseEntity<List<ChatMessageResponse>> messages(
        @PathVariable Long id,
        @RequestParam(required = false) Long before,
        @RequestParam(required = false) Integer limit
    ) {
        return ResponseEntity.ok(chatService.messages(id, before, limit));
    }

    @WorkspaceWrite
    @PostMapping("/channels/{id}/messages")
    public ResponseEntity<ChatMessageResponse> postMessage(
        @PathVariable Long id,
        @Valid @RequestBody ChatMessageRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(chatService.postMessage(id, request.body()));
    }

    @WorkspaceWrite
    @PatchMapping("/messages/{id}")
    public ResponseEntity<ChatMessageResponse> editMessage(
        @PathVariable Long id,
        @Valid @RequestBody ChatMessageRequest request
    ) {
        return ResponseEntity.ok(chatService.editMessage(id, request.body()));
    }

    @WorkspaceWrite
    @DeleteMapping("/messages/{id}")
    public ResponseEntity<Void> deleteMessage(@PathVariable Long id) {
        chatService.deleteMessage(id);
        return ResponseEntity.noContent().build();
    }

    @WorkspaceWrite
    @PostMapping("/messages/{id}/reactions")
    public ResponseEntity<ChatMessageResponse> react(
        @PathVariable Long id,
        @Valid @RequestBody ChatReactionRequest request
    ) {
        return ResponseEntity.ok(chatService.react(id, request.emoji(), request.add()));
    }

    // ---------- read state / search / presence ----------

    @PostMapping("/channels/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id) {
        chatService.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/unread")
    public ResponseEntity<ChatUnreadResponse> unread() {
        return ResponseEntity.ok(chatService.unread());
    }

    @GetMapping("/search")
    public ResponseEntity<List<ChatMessageResponse>> search(@RequestParam("q") String q) {
        return ResponseEntity.ok(chatService.search(q));
    }

    @GetMapping("/presence")
    public ResponseEntity<PresenceResponse> presence() {
        return ResponseEntity.ok(new PresenceResponse(presence.onlineUserIds()));
    }

    public record UpdateTopicRequest(String topic) {}

    public record PresenceResponse(List<Long> onlineUserIds) {}
}
