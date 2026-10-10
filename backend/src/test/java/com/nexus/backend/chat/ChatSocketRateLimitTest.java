package com.nexus.backend.chat;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.repository.ChatChannelMemberRepository;
import com.nexus.backend.repository.ChatChannelRepository;
import com.nexus.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An authenticated chat socket is still an untrusted client: it may not send
 * unbounded frames or flood the relay.
 */
@ExtendWith(MockitoExtension.class)
class ChatSocketRateLimitTest {

    @Mock private ChatChannelMemberRepository memberRepository;
    @Mock private ChatChannelRepository channelRepository;
    @Mock private UserRepository userRepository;
    @Mock private WebSocketSession session;
    @Mock private User user;

    private ChatSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ChatSocketHandler(memberRepository, channelRepository, userRepository,
            1024, 3, 60);
    }

    private void connect() {
        when(session.getAttributes()).thenReturn(Map.of("email", "m@nexus.com"));
        when(userRepository.findByEmail("m@nexus.com")).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(7L);
        handler.afterConnectionEstablished(session);
    }

    @Test
    void boundsInboundFramesOnConnect() {
        connect();

        verify(session).setTextMessageSizeLimit(1024);
        verify(session).setBinaryMessageSizeLimit(1024);
    }

    @Test
    void closesASocketThatFloodsFrames() throws Exception {
        when(session.getId()).thenReturn("s1");
        connect();

        handler.handleTextMessage(session, new TextMessage("{}"));
        handler.handleTextMessage(session, new TextMessage("{}"));
        handler.handleTextMessage(session, new TextMessage("{}"));
        verify(session, times(0)).close(any());

        handler.handleTextMessage(session, new TextMessage("{}"));
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }
}
