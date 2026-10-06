package com.nexus.backend.service;

import com.nexus.backend.chat.ChatSocketHandler;
import org.springframework.stereotype.Component;

import java.util.List;

/** Thin facade over the chat socket for services that need presence only. */
@Component
public class ChatSocketFacade {

    private final ChatSocketHandler socketHandler;

    public ChatSocketFacade(ChatSocketHandler socketHandler) {
        this.socketHandler = socketHandler;
    }

    public List<Long> onlineUserIds() {
        return socketHandler.onlineUserIds();
    }
}
