package com.nexus.backend.chat;

import com.nexus.backend.repository.ChatChannelMemberRepository;
import com.nexus.backend.repository.ChatChannelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An authenticated chat socket is still an untrusted client: it may not send
 * unbounded frames or flood the relay.
 *
 * <p>The identity comes from the handshake's session attributes now — the
 * connection is refused without them — so this connects the way the real
 * handshake does rather than by stubbing a lookup the handler no longer makes.
 */
@ExtendWith(MockitoExtension.class)
class ChatSocketRateLimitTest {

    @Mock private ChatChannelMemberRepository memberRepository;
    @Mock private ChatChannelRepository channelRepository;
    @Mock private WebSocketSession session;

    private ChatSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ChatSocketHandler(memberRepository, channelRepository, 1024, 3, 60);
    }

    private void connect() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("email", "m@nexus.com");
        attributes.put(ChatSocketConfig.USER_ATTRIBUTE, 7L);
        attributes.put(ChatSocketConfig.COMPANY_ATTRIBUTE, 3L);
        when(session.getAttributes()).thenReturn(attributes);
        handler.afterConnectionEstablished(session);
    }

    @Test
    void boundsInboundFramesOnConnect() {
        connect();

        verify(session).setTextMessageSizeLimit(1024);
        verify(session).setBinaryMessageSizeLimit(1024);
    }

    /** A handshake that named no company cannot be attributed, so it is closed. */
    @Test
    void closesASocketTheHandshakeCouldNotAttribute() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("email", "m@nexus.com");
        when(session.getAttributes()).thenReturn(attributes);

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void closesASocketThatFloodsFrames() throws Exception {
        when(session.getId()).thenReturn("s1");
        connect();

        handler.handleTextMessage(session, new TextMessage("{ }"));
        handler.handleTextMessage(session, new TextMessage("{ }"));
        handler.handleTextMessage(session, new TextMessage("{ }"));
        verify(session, times(0)).close(any());

        handler.handleTextMessage(session, new TextMessage("{ }"));
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }
}
