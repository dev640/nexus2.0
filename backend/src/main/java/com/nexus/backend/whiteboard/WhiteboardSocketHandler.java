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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps every live whiteboard socket and broadcasts JSON events to the sockets
 * of one company.
 *
 * <p>The company is part of the client record rather than a property of the
 * note, because the audience is decided per connection: a note's company is
 * checked against each session's, and a session whose company is unknown is
 * closed at connection time rather than being left in a set that has to be
 * filtered later. A note carries the text people wrote, so delivering one to the
 * wrong company is a disclosure and not a display bug.
 */
@Component
public class WhiteboardSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardSocketHandler.class);

    /** One socket and the company it belongs to. */
    private record Client(WebSocketSession session, Long companyId) {}

    private final Map<String, Client> clients = new ConcurrentHashMap<>();
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

        Long companyId = companyIdOf(session);
        if (companyId == null) {
            // The handshake is supposed to have refused this. Closing here is the
            // backstop: a socket with no company could not be sent a correct
            // subset of events, and the only safe audience is none.
            log.warn("Closing whiteboard session {}: no company on the handshake", session.getId());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        clients.put(session.getId(), new Client(session, companyId));
        log.debug("Whiteboard client connected ({} live)", clients.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
    }

    /**
     * Send one event to the sockets of {@code companyId}.
     *
     * <p>A null company is refused instead of treated as a wildcard. There is no
     * correct audience for an event that cannot name its company, and the wrong
     * guess hands one company's note to every other one.
     */
    public void broadcast(String payload, Long companyId) {
        if (companyId == null) {
            log.warn("Refusing to broadcast a whiteboard event that names no company");
            return;
        }
        TextMessage message = new TextMessage(payload);
        for (Client client : clients.values()) {
            if (!companyId.equals(client.companyId())) {
                continue;
            }
            send(client.session(), message);
        }
    }

    /**
     * Close and forget every socket belonging to one company. Used when a
     * company is suspended: a suspended workspace must not keep reading its
     * board over a connection that was opened while it was active.
     */
    public void closeCompany(Long companyId) {
        if (companyId == null) {
            return;
        }
        for (Map.Entry<String, Client> entry : clients.entrySet()) {
            if (companyId.equals(entry.getValue().companyId())) {
                clients.remove(entry.getKey());
                closeQuietly(entry.getValue().session(), CloseStatus.POLICY_VIOLATION);
            }
        }
    }

    /** How many sockets are connected, for diagnostics and tests. */
    public int connectedCount() {
        return clients.size();
    }

    private static Long companyIdOf(WebSocketSession session) {
        Object companyId = session.getAttributes().get(WhiteboardSocketConfig.COMPANY_ATTRIBUTE);
        return companyId instanceof Long value ? value : null;
    }

    private void send(WebSocketSession session, TextMessage message) {
        if (session == null || !session.isOpen()) {
            clients.remove(session == null ? "" : session.getId());
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(message);
            }
        } catch (IOException e) {
            log.debug("Dropping whiteboard session {}: {}", session.getId(), e.getMessage());
            clients.remove(session.getId());
        }
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
            // already gone
        }
    }
}
