package com.nexus.backend.whiteboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps every live whiteboard socket and broadcasts JSON events to all of them. */
@Component
public class WhiteboardSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardSocketHandler.class);

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final int maxTextMessageBytes;

    public WhiteboardSocketHandler(
        @Value("${nexus.ws.max-text-message-bytes:65536}") int maxTextMessageBytes
    ) {
        this.maxTextMessageBytes = maxTextMessageBytes;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Clients only listen on this socket; bound the frames they may send anyway.
        session.setTextMessageSizeLimit(maxTextMessageBytes);
        session.setBinaryMessageSizeLimit(maxTextMessageBytes);
        sessions.add(session);
        log.debug("Whiteboard client connected ({} live)", sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    public void broadcast(String payload) {
        TextMessage message = new TextMessage(payload);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (IOException e) {
                log.debug("Dropping whiteboard session {}: {}", session.getId(), e.getMessage());
                sessions.remove(session);
            }
        }
    }
}
