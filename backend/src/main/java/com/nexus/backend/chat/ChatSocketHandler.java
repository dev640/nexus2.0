package com.nexus.backend.chat;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.nexus.backend.domain.chat.ChatChannel;
import com.nexus.backend.dto.ChatEvent;
import com.nexus.backend.repository.ChatChannelMemberRepository;
import com.nexus.backend.repository.ChatChannelRepository;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live chat socket. Each connection belongs to one authenticated user (the
 * handshake stores the email, the user id and the company in the session
 * attributes); incoming frames are typing indicators, outgoing frames are
 * {@link ChatEvent}s addressed to the users who should see them.
 *
 * <p>Every delivery decides its audience by company first. Membership of a
 * channel is a company-scoped fact and presence is a workspace fact, so a
 * connection that cannot name its company is closed rather than left in a
 * registry that every broadcast would have to filter.
 */
@Component
public class ChatSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatSocketHandler.class);

    /** One socket, the account behind it, and the company it belongs to. */
    private record Client(Long userId, Long companyId) {}

    private final Map<WebSocketSession, Client> users = new ConcurrentHashMap<>();

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

    public ChatSocketHandler(
        ChatChannelMemberRepository memberRepository,
        ChatChannelRepository channelRepository,
        @Value("${nexus.ws.max-text-message-bytes:65536}") int maxTextMessageBytes,
        @Value("${nexus.ws.max-messages-per-window:120}") int maxMessagesPerWindow,
        @Value("${nexus.ws.message-window-seconds:60}") long messageWindowSeconds
    ) {
        this.memberRepository = memberRepository;
        this.channelRepository = channelRepository;
        this.maxTextMessageBytes = maxTextMessageBytes;
        this.maxMessagesPerWindow = maxMessagesPerWindow;
        this.messageWindowMillis = messageWindowSeconds * 1000;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Bound inbound frames before the socket is used for anything else.
        session.setTextMessageSizeLimit(maxTextMessageBytes);
        session.setBinaryMessageSizeLimit(maxTextMessageBytes);

        Long userId = attribute(session, ChatSocketConfig.USER_ATTRIBUTE);
        Long companyId = attribute(session, ChatSocketConfig.COMPANY_ATTRIBUTE);
        if (userId == null || companyId == null) {
            // The handshake is supposed to have refused this. Closing here is the
            // backstop, because a socket with no company could not be sent a
            // correct subset of events and the only safe audience is none.
            log.warn("Closing chat session {}: the handshake named no account or company", session.getId());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        users.put(session, new Client(userId, companyId));
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
        Client client = users.get(session);
        if (client == null) return;
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
                if (channelId > 0 && memberRepository.existsByChannelIdAndUserId(channelId, client.userId())) {
                    sendToChannel(channelId, new ChatEvent(ChatEvent.TYPING, channelId, null, null,
                        client.userId(), frame.path("name").asText("Someone"), channelName(channelId), null),
                        client.userId());
                }
            }
            // "read" frames are persisted over REST; nothing to relay here.
        } catch (Exception e) {
            log.debug("Ignoring malformed chat frame: {}", e.getMessage());
        }
    }

    /**
     * Send an event to every connected member of the channel (except one user).
     *
     * <p>Two facts have to agree before a frame is written: the recipient is a
     * member of the channel, and the recipient is in the channel's company. The
     * second is not redundant - membership rows are company-scoped today, so a
     * mismatch would mean one of the two facts is already wrong, and a broadcast
     * is the wrong place to discover that.
     */
    public void sendToChannel(Long channelId, ChatEvent event, Long excludeUserId) {
        String payload = write(event);
        if (payload == null) return;
        Long companyId = companyIdOfChannel(channelId);
        if (companyId == null) {
            log.warn("Not sending a chat event for channel {}: it names no company", channelId);
            return;
        }
        Set<Long> memberIds = new HashSet<>(memberRepository.findByChannelId(channelId)
            .stream().map(m -> m.getUser().getId()).toList());
        for (Map.Entry<WebSocketSession, Client> entry : users.entrySet()) {
            Client client = entry.getValue();
            if (!companyId.equals(client.companyId())) continue;
            if (client.userId().equals(excludeUserId) || !memberIds.contains(client.userId())) continue;
            send(entry.getKey(), payload);
        }
    }

    /** Send an event to specific users, whichever instance holds their socket. */
    public void sendToUsers(List<Long> userIds, ChatEvent event) {
        String payload = write(event);
        if (payload == null) return;
        Set<Long> targets = new HashSet<>(userIds);
        for (Map.Entry<WebSocketSession, Client> entry : users.entrySet()) {
            if (targets.contains(entry.getValue().userId())) send(entry.getKey(), payload);
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
        for (Map.Entry<WebSocketSession, Client> entry : users.entrySet()) {
            if (targets.contains(entry.getValue().userId())) send(entry.getKey(), json);
        }
    }

    /**
     * Tell each connection who is online <em>in its own company</em>.
     *
     * <p>Presence is shown as a workspace roster, so one company's members are
     * the only accounts that can answer it. Sending the global list would tell
     * every workspace how many people every other workspace has signed in, which
     * is neither useful to them nor theirs to know.
     */
    public void broadcastPresence() {
        Map<Long, List<Long>> onlineByCompany = new HashMap<>();
        for (Client client : users.values()) {
            onlineByCompany.computeIfAbsent(client.companyId(), company -> new ArrayList<>())
                .add(client.userId());
        }
        for (Map.Entry<WebSocketSession, Client> entry : users.entrySet()) {
            List<Long> online = distinct(onlineByCompany.get(entry.getValue().companyId()));
            send(entry.getKey(), write(Map.of("type", ChatEvent.PRESENCE, "online", online)));
        }
    }

    /** The accounts online in one company, which is what that company's UI shows. */
    public List<Long> onlineUserIds(Long companyId) {
        if (companyId == null) return List.of();
        List<Long> ids = new ArrayList<>();
        for (Client client : users.values()) {
            if (companyId.equals(client.companyId())) ids.add(client.userId());
        }
        return distinct(ids);
    }

    /** How many sockets are connected, for diagnostics and tests. */
    public int connectedCount() {
        return users.size();
    }

    /**
     * Close and forget every socket belonging to one company. Used when a
     * company is suspended: the stream carries the same traffic its HTTP
     * requests are being refused, so it cannot stay open.
     */
    public void closeCompany(Long companyId) {
        if (companyId == null) return;
        for (Map.Entry<WebSocketSession, Client> entry : users.entrySet()) {
            if (companyId.equals(entry.getValue().companyId())) {
                users.remove(entry.getKey());
                messageWindows.remove(entry.getKey().getId());
                closeQuietly(entry.getKey(), CloseStatus.POLICY_VIOLATION);
            }
        }
    }

    private Long companyIdOfChannel(Long channelId) {
        return channelRepository.findById(channelId).map(ChatChannel::getCompanyId).orElse(null);
    }

    private String channelName(Long channelId) {
        return channelRepository.findById(channelId).map(ChatChannel::getName).orElse(null);
    }

    /** A session attribute written by the handshake, or null when it is missing. */
    private static Long attribute(WebSocketSession session, String name) {
        Object value = session.getAttributes().get(name);
        return value instanceof Long number ? number : null;
    }

    private static List<Long> distinct(List<Long> ids) {
        if (ids == null) return List.of();
        return new ArrayList<>(new HashSet<>(ids));
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
        if (session == null || payload == null || !session.isOpen()) return;
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
