package com.nexus.backend.chat;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.nexus.backend.dto.ChatEvent;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.repository.ChatChannelMemberRepository;
import com.nexus.backend.repository.ChatChannelRepository;
import com.nexus.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live chat socket. Each connection belongs to one authenticated user (the
 * handshake stores the email in the session attributes); incoming frames are
 * typing indicators, outgoing frames are {@link ChatEvent}s addressed to the
 * users who should see them.
 */
@Component
public class ChatSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatSocketHandler.class);

    private final Map<WebSocketSession, Long> users = new ConcurrentHashMap<>();

    /** Per-connection inbound frame budget: an open socket cannot be used as a flood source. */
    private record MessageWindow(long startMillis, int count) {}

    private final Map<String, MessageWindow> messageWindows = new ConcurrentHashMap<>();
    private final int maxTextMessageBytes;
    private final int maxMessagesPerWindow;
    private final long messageWindowMillis;

    /**
     * Jackson 2 without the jsr310 module cannot serialize {@link LocalDateTime},
     * which message payloads carry — events would silently fail to write. This
     * serializer emits the same ISO local date-time format as the REST layer.
     */
    private static ObjectMapper eventMapper() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule javaTime = new SimpleModule();
        javaTime.addSerializer(LocalDateTime.class, new JsonSerializer<>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value));
            }
        });
        mapper.registerModule(javaTime);
        return mapper;
    }

    private final ObjectMapper objectMapper = eventMapper();
    private final ChatChannelMemberRepository memberRepository;
    private final ChatChannelRepository channelRepository;
    private final UserRepository userRepository;

    public ChatSocketHandler(
        ChatChannelMemberRepository memberRepository,
        ChatChannelRepository channelRepository,
        UserRepository userRepository,
        @Value("${nexus.ws.max-text-message-bytes:65536}") int maxTextMessageBytes,
        @Value("${nexus.ws.max-messages-per-window:120}") int maxMessagesPerWindow,
        @Value("${nexus.ws.message-window-seconds:60}") long messageWindowSeconds
    ) {
        this.memberRepository = memberRepository;
        this.channelRepository = channelRepository;
        this.userRepository = userRepository;
        this.maxTextMessageBytes = maxTextMessageBytes;
        this.maxMessagesPerWindow = maxMessagesPerWindow;
        this.messageWindowMillis = messageWindowSeconds * 1000;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Bound inbound frames before the socket is used for anything else.
        session.setTextMessageSizeLimit(maxTextMessageBytes);
        session.setBinaryMessageSizeLimit(maxTextMessageBytes);

        Long userId = userIdOf(session);
        if (userId == null) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        users.put(session, userId);
        broadcastPresence();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        users.remove(session);
        messageWindows.remove(session.getId());
        broadcastPresence();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = users.get(session);
        if (userId == null) return;
        if (!withinMessageBudget(session)) {
            log.debug("Closing chat session {}: message rate exceeded", session.getId());
            users.remove(session);
            messageWindows.remove(session.getId());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            JsonNode frame = objectMapper.readTree(message.getPayload());
            String type = frame.path("type").asText("");
            if (ChatEvent.TYPING.equals(type)) {
                Long channelId = frame.path("channelId").asLong(0);
                if (channelId > 0 && memberRepository.existsByChannelIdAndUserId(channelId, userId)) {
                    sendToChannel(channelId, new ChatEvent(ChatEvent.TYPING, channelId, null, null,
                        userId, frame.path("name").asText("Someone"), channelName(channelId), null), userId);
                }
            }
            // "read" frames are persisted over REST; nothing to relay here.
        } catch (Exception e) {
            log.debug("Ignoring malformed chat frame: {}", e.getMessage());
        }
    }

    /** Send an event to every connected member of the channel (except one user). */
    public void sendToChannel(Long channelId, ChatEvent event, Long excludeUserId) {
        String payload = write(event);
        if (payload == null) return;
        ChatChannelMemberRepository repo = memberRepository;
        Set<Long> memberIds = new HashSet<>(repo.findByChannelId(channelId)
            .stream().map(m -> m.getUser().getId()).toList());
        for (Map.Entry<WebSocketSession, Long> entry : users.entrySet()) {
            Long userId = entry.getValue();
            if (userId.equals(excludeUserId) || !memberIds.contains(userId)) continue;
            send(entry.getKey(), payload);
        }
    }

    /** Send an event to specific users, whichever instance holds their socket. */
    public void sendToUsers(List<Long> userIds, ChatEvent event) {
        String payload = write(event);
        if (payload == null) return;
        Set<Long> targets = new HashSet<>(userIds);
        for (Map.Entry<WebSocketSession, Long> entry : users.entrySet()) {
            if (targets.contains(entry.getValue())) send(entry.getKey(), payload);
        }
    }

    /**
     * Push an arbitrary JSON frame to specific users. Notifications travel this
     * way: they are not chat events, but the session registry that knows which
     * socket belongs to whom is the same one.
     */
    public void pushToUsers(List<Long> userIds, Object payload) {
        String json = write(payload);
        if (json == null) return;
        Set<Long> targets = new HashSet<>(userIds);
        for (Map.Entry<WebSocketSession, Long> entry : users.entrySet()) {
            if (targets.contains(entry.getValue())) send(entry.getKey(), json);
        }
    }

    /** Broadcast the current online user ids to everyone (presence badges). */
    public void broadcastPresence() {
        String payload = write(Map.of("type", ChatEvent.PRESENCE, "online", onlineUserIds()));
        if (payload == null) return;
        for (WebSocketSession session : users.keySet()) {
            send(session, payload);
        }
    }

    public List<Long> onlineUserIds() {
        return new ArrayList<>(new HashSet<>(users.values()));
    }

    private String channelName(Long channelId) {
        return channelRepository.findById(channelId).map(c -> c.getName()).orElse(null);
    }

    private Long userIdOf(WebSocketSession session) {
        Object email = session.getAttributes().get("email");
        if (email == null) return null;
        return userRepository.findByEmail(email.toString()).map(User::getId).orElse(null);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not serialize chat event: {}", e.getMessage());
            return null;
        }
    }

    private void send(WebSocketSession session, String payload) {
        if (session == null || !session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException e) {
            log.debug("Dropping chat session {}: {}", session.getId(), e.getMessage());
            users.remove(session);
            messageWindows.remove(session.getId());
        }
    }

    /** False once the connection has spent its inbound frame budget for the window. */
    private boolean withinMessageBudget(WebSocketSession session) {
        long now = System.currentTimeMillis();
        MessageWindow window = messageWindows.get(session.getId());
        if (window == null || now - window.startMillis() > messageWindowMillis) {
            messageWindows.put(session.getId(), new MessageWindow(now, 1));
            return true;
        }
        if (window.count() >= maxMessagesPerWindow) {
            return false;
        }
        messageWindows.put(session.getId(), new MessageWindow(window.startMillis(), window.count() + 1));
        return true;
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
            // already gone
        }
    }
}
