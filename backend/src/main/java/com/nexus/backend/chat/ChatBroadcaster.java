package com.nexus.backend.chat;

import com.nexus.backend.domain.chat.ChatChannelMember;
import com.nexus.backend.dto.ChatEvent;
import com.nexus.backend.repository.ChatChannelMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Delivers chat events from {@code ChatService} to live sockets, scoped by
 * channel membership. Cross-instance fan-out via Redis can be added here later
 * without touching callers — every send already goes through this class.
 */
@Component
public class ChatBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ChatBroadcaster.class);

    private final ChatSocketHandler socketHandler;

    public ChatBroadcaster(ChatSocketHandler socketHandler) {
        this.socketHandler = socketHandler;
    }

    /**
     * Deliver an event to everyone who should see it: members of the channel
     * (excluding the acting user for typing indicators, whose UI shows it
     * locally), or a fixed user list for non-channel events.
     */
    public void broadcast(ChatEvent event) {
        try {
            if (event.channelId() != null) {
                socketHandler.sendToChannel(event.channelId(), event,
                    ChatEvent.TYPING.equals(event.type()) ? event.userId() : null);
            } else if (event.userId() != null) {
                socketHandler.sendToUsers(List.of(event.userId()), event);
            } else {
                socketHandler.sendToUsers(List.of(), event);
            }
        } catch (Exception e) {
            log.warn("Failed to broadcast chat event: {}", e.getMessage());
        }
    }
}
