package com.nexus.backend.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.backend.chat.ChatSocketConfig;
import com.nexus.backend.chat.ChatSocketHandler;
import com.nexus.backend.dto.WhiteboardEvent;
import com.nexus.backend.dto.WhiteboardNoteResponse;
import com.nexus.backend.repository.ChatChannelMemberRepository;
import com.nexus.backend.repository.ChatChannelRepository;
import com.nexus.backend.whiteboard.WhiteboardBroadcaster;
import com.nexus.backend.whiteboard.WhiteboardSocketConfig;
import com.nexus.backend.whiteboard.WhiteboardSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A broadcast socket is the one place where "send to everybody who is
 * connected" is the natural implementation, and it is exactly wrong once two
 * companies share a process: every open tab belongs to somebody, and most of
 * them belong to somebody else.
 *
 * <p>These tests hold both sockets to the same rule the database enforces - one
 * company's event reaches one company's sessions - and to the failure mode that
 * makes the rule safe: an event that cannot name its company is dropped rather
 * than delivered to everyone.
 */
class TenantFanOutIsolationTest {

    private static final Long COMPANY_A = 1L;
    private static final Long COMPANY_B = 2L;

    private final ObjectMapper json = new ObjectMapper();

    // ------------------------------------------------------------- whiteboard

    /**
     * The note carries the text people wrote, so an event for one company must
     * not reach another company's socket. This is the leak as it was found: a
     * flat set of sessions and a broadcast that sent the note to all of them.
     */
    @Test
    void aWhiteboardEventDoesNotReachAnotherCompanysSession() throws Exception {
        WhiteboardSocketHandler handler = new WhiteboardSocketHandler(65536);
        Client a = whiteboardClient(handler, COMPANY_A, "a");
        Client b = whiteboardClient(handler, COMPANY_B, "b");

        broadcaster(handler).publish("created", note(), COMPANY_A);

        assertThat(a.frames()).hasSize(1);
        assertThat(a.frames().get(0)).contains("\"text\":\"secret\"");
        assertThat(b.frames()).isEmpty();
    }

    /**
     * The same rule on the relay path: an event that arrives from another
     * instance over Redis is scoped by this instance too, because the publisher
     * being a different process is not evidence about what to send here.
     */
    @Test
    void aRelayedWhiteboardEventIsFilteredByCompanyAsWell() throws Exception {
        WhiteboardSocketHandler handler = new WhiteboardSocketHandler(65536);
        Client a = whiteboardClient(handler, COMPANY_A, "a");
        Client b = whiteboardClient(handler, COMPANY_B, "b");

        broadcaster(handler).deliver(
            new WhiteboardEvent("updated", note(), COMPANY_B, "some-other-instance"));

        assertThat(a.frames()).isEmpty();
        assertThat(b.frames()).hasSize(1);
    }

    /** An event that cannot name its company has no correct audience. */
    @Test
    void aWhiteboardEventWithNoCompanyIsDroppedRatherThanBroadcast() throws Exception {
        WhiteboardSocketHandler handler = new WhiteboardSocketHandler(65536);
        Client a = whiteboardClient(handler, COMPANY_A, "a");
        Client b = whiteboardClient(handler, COMPANY_B, "b");

        WhiteboardBroadcaster broadcaster = broadcaster(handler);
        broadcaster.publish("created", note(), null);
        broadcaster.deliver(new WhiteboardEvent("created", note(), null, "some-other-instance"));

        assertThat(a.frames()).isEmpty();
        assertThat(b.frames()).isEmpty();
    }

    /** A socket the handshake could not attribute is closed, not kept unfiltered. */
    @Test
    void aWhiteboardSocketWithNoCompanyIsClosed() throws Exception {
        WhiteboardSocketHandler handler = new WhiteboardSocketHandler(65536);
        WebSocketSession session = mockSession("orphan", Map.of("email", "x@test"));

        handler.afterConnectionEstablished(session);

        org.mockito.Mockito.verify(session).close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
        assertThat(handler.connectedCount()).isZero();
    }

    // ------------------------------------------------------------------- chat

    /**
     * Presence is a workspace fact, not a platform one. A member of one company
     * has no business knowing which accounts in another company are online, and
     * before this the presence frame listed every user on the instance.
     */
    @Test
    void presenceIsReportedPerCompany() throws Exception {
        ChatSocketHandler handler = chatHandler();
        Client a = chatClient(handler, 11L, COMPANY_A, "a");
        Client b = chatClient(handler, 22L, COMPANY_B, "b");

        handler.broadcastPresence();

        assertThat(onlineIdsOf(a.lastFrame())).containsExactly(11L);
        assertThat(onlineIdsOf(b.lastFrame())).containsExactly(22L);
    }

    /** The REST presence endpoint answers for the caller's company only. */
    @Test
    void onlineUsersAreListedPerCompany() throws Exception {
        ChatSocketHandler handler = chatHandler();
        chatClient(handler, 11L, COMPANY_A, "a");
        chatClient(handler, 12L, COMPANY_A, "a2");
        chatClient(handler, 22L, COMPANY_B, "b");

        assertThat(handler.onlineUserIds(COMPANY_A)).containsExactlyInAnyOrder(11L, 12L);
        assertThat(handler.onlineUserIds(COMPANY_B)).containsExactly(22L);
        assertThat(handler.onlineUserIds(null)).isEmpty();
    }

    // -------------------------------------------------------------- fixtures

    /** One socket and the frames it was sent. */
    private record Client(WebSocketSession session, List<String> frames) {
        String lastFrame() {
            return frames.isEmpty() ? null : frames.get(frames.size() - 1);
        }
    }

    private List<Long> onlineIdsOf(String frame) throws Exception {
        JsonNode node = json.readTree(frame);
        List<Long> ids = new ArrayList<>();
        node.path("online").forEach(id -> ids.add(id.asLong()));
        return ids;
    }

    private static WhiteboardBroadcaster broadcaster(WhiteboardSocketHandler handler) {
        return new WhiteboardBroadcaster(handler, noRedis());
    }

    private static Client whiteboardClient(WhiteboardSocketHandler handler, Long companyId, String id)
            throws Exception {
        Client client = client(id, Map.of(
            "email", id + "@test",
            WhiteboardSocketConfig.COMPANY_ATTRIBUTE, companyId));
        handler.afterConnectionEstablished(client.session());
        return client;
    }

    private static Client chatClient(ChatSocketHandler handler, Long userId, Long companyId, String id)
            throws Exception {
        Client client = client(id, Map.of(
            "email", id + "@test",
            ChatSocketConfig.USER_ATTRIBUTE, userId,
            ChatSocketConfig.COMPANY_ATTRIBUTE, companyId));
        handler.afterConnectionEstablished(client.session());
        return client;
    }

    /** A live session whose sends are recorded instead of written to a wire. */
    private static Client client(String id, Map<String, ?> suppliedAttributes) throws Exception {
        WebSocketSession session = mockSession(id, suppliedAttributes);
        List<String> frames = new ArrayList<>();
        doAnswer(invocation -> {
            frames.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any());
        return new Client(session, frames);
    }

    private static WebSocketSession mockSession(String id, Map<String, ?> suppliedAttributes)
            throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new HashMap<>(suppliedAttributes));
        return session;
    }

    private static ChatSocketHandler chatHandler() {
        return new ChatSocketHandler(
            mock(ChatChannelMemberRepository.class),
            mock(ChatChannelRepository.class),
            65536,
            120,
            60);
    }

    private static WhiteboardNoteResponse note() {
        // The DTO has no company on purpose - a client is never told whose note it
        // is - so the event is what must carry it.
        return new WhiteboardNoteResponse(7L, "default", "secret", "yellow", 10.0, 10.0, "ada@a.test");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> noRedis() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
