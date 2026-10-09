package com.nexus.backend.service;

import com.nexus.backend.chat.ChatSocketHandler;
import com.nexus.backend.dto.ChatEvent;
import com.nexus.backend.dto.NotificationResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Thin facade over the chat socket for services that need presence or a push.
 * Notifications ride the chat connection because it already identifies the
 * signed-in user; nothing about them is chat-specific beyond that.
 */
@Component
public class ChatSocketFacade {

    private final ChatSocketHandler socketHandler;

    public ChatSocketFacade(ChatSocketHandler socketHandler) {
        this.socketHandler = socketHandler;
    }

    public List<Long> onlineUserIds() {
        return socketHandler.onlineUserIds();
    }

    /**
     * Pushes one notification to one user's live session, if they have one.
     * Offline users lose nothing: the row is stored and shows in their next load.
     */
    public void pushNotification(Long userId, NotificationResponse notification) {
        if (userId == null || notification == null) return;
        socketHandler.pushToUsers(List.of(userId), Map.of(
            "type", ChatEvent.NOTIFICATION_CREATED,
            "notification", notification
        ));
    }
}
