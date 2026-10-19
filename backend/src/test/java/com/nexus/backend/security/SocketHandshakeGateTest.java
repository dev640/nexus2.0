package com.nexus.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.backend.chat.ChatSocketConfig;
import com.nexus.backend.chat.ChatSocketHandler;
import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.service.SupabaseUserService;
import com.nexus.backend.whiteboard.WhiteboardSocketConfig;
import com.nexus.backend.whiteboard.WhiteboardSocketHandler;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Both sockets hold the password-change gate.
 *
 * <p>The HTTP filter cannot cover these requests: a browser opens a socket
 * without an Authorization header, so no principal is ever in scope at
 * handshake time and the filter would let them through. That leaves a hole the
 * feature exists to close — an account refused every workspace endpoint would
 * still stream chat and whiteboard traffic — so the rule is applied where the
 * token is actually read, and these tests are what proves it is.
 */
class SocketHandshakeGateTest {

    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final SupabaseTokenVerifier supabaseTokenVerifier = mock(SupabaseTokenVerifier.class);
    private final SupabaseUserService supabaseUserService = mock(SupabaseUserService.class);
    private final SupabaseProperties supabaseProperties = mock(SupabaseProperties.class);
    private final MustChangePasswordHandshakeGuard guard = mock(MustChangePasswordHandshakeGuard.class);
    private final SocketPrincipalResolver principalResolver = mock(SocketPrincipalResolver.class);

    /** How one handshake went, and what it recorded about the account. */
    private record Outcome(boolean allowed, Map<String, Object> attributes) {}

    // ------------------------------------------------------------- the refusal

    @Test
    void chatRefusesAnAccountThatMustChangeItsPassword() throws Exception {
        HandshakeInterceptor chat = chatInterceptor();
        nexusToken("tok", "ada@nexus.com");
        when(guard.mustChangePassword("ada@nexus.com")).thenReturn(true);

        assertThat(chat(chat, "/ws/chat", "tok").allowed()).isFalse();
        verify(guard).mustChangePassword("ada@nexus.com");
    }

    @Test
    void whiteboardRefusesAnAccountThatMustChangeItsPassword() throws Exception {
        HandshakeInterceptor whiteboard = whiteboardInterceptor();
        nexusToken("tok", "ada@nexus.com");
        when(guard.mustChangePassword("ada@nexus.com")).thenReturn(true);

        assertThat(chat(whiteboard, "/ws/whiteboard", "tok").allowed()).isFalse();
    }

    /** The account is already loaded on the Supabase path, so the rule is read from it. */
    @Test
    void aSupabaseAccountIsHeldToTheSameRule() throws Exception {
        HandshakeInterceptor chat = chatInterceptor();
        when(supabaseProperties.isEnabled()).thenReturn(true);
        Claims claims = mock(Claims.class);
        when(supabaseTokenVerifier.verify("tok")).thenReturn(claims);
        when(supabaseUserService.resolveUser(claims)).thenReturn(account(true));

        assertThat(chat(chat, "/ws/chat", "tok").allowed()).isFalse();
    }

    // ----------------------------------------------------------- the way through

    @Test
    void chatLetsAnAccountThatHasChangedItsPassword() throws Exception {
        HandshakeInterceptor chat = chatInterceptor();
        nexusToken("tok", "ada@nexus.com");
        when(guard.mustChangePassword("ada@nexus.com")).thenReturn(false);
        identity("ada@nexus.com");

        Outcome outcome = chat(chat, "/ws/chat", "tok");

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.attributes()).containsEntry("email", "ada@nexus.com");
        // The company is what every fan-out decision filters on, so a socket
        // that opened without one would be a socket nobody could address safely.
        assertThat(outcome.attributes()).containsEntry(ChatSocketConfig.COMPANY_ATTRIBUTE, 3L);
    }

    @Test
    void whiteboardLetsAnAccountThatHasChangedItsPassword() throws Exception {
        HandshakeInterceptor whiteboard = whiteboardInterceptor();
        nexusToken("tok", "ada@nexus.com");
        when(guard.mustChangePassword("ada@nexus.com")).thenReturn(false);
        identity("ada@nexus.com");

        Outcome outcome = chat(whiteboard, "/ws/whiteboard", "tok");

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.attributes())
            .containsEntry(WhiteboardSocketConfig.COMPANY_ATTRIBUTE, 3L);
    }

    @Test
    void chatRefusesATokenFromBeforeThePasswordChange() throws Exception {
        HandshakeInterceptor chat = chatInterceptor();
        nexusToken("tok", "ada@nexus.com");
        when(guard.mustChangePassword("ada@nexus.com")).thenReturn(false);
        identity("ada@nexus.com", 1);
        when(jwtUtil.validateToken("tok", "ada@nexus.com", 1)).thenReturn(false);

        assertThat(chat(chat, "/ws/chat", "tok").allowed()).isFalse();
    }

    @Test
    void whiteboardRefusesATokenFromBeforeThePasswordChange() throws Exception {
        HandshakeInterceptor whiteboard = whiteboardInterceptor();
        nexusToken("tok", "ada@nexus.com");
        when(guard.mustChangePassword("ada@nexus.com")).thenReturn(false);
        identity("ada@nexus.com", 1);
        when(jwtUtil.validateToken("tok", "ada@nexus.com", 1)).thenReturn(false);

        assertThat(chat(whiteboard, "/ws/whiteboard", "tok").allowed()).isFalse();
    }

    /**
     * An operator account belongs to no company, so there is no company's traffic
     * to subscribe it to: the socket is refused rather than left unattributed.
     */
    @Test
    void aSocketWithNoCompanyToAttributeIsRefused() throws Exception {
        HandshakeInterceptor chat = chatInterceptor();
        nexusToken("tok", "root@nexus.com");
        when(guard.mustChangePassword("root@nexus.com")).thenReturn(false);
        when(principalResolver.resolve("root@nexus.com")).thenReturn(java.util.Optional.empty());

        assertThat(chat(chat, "/ws/chat", "tok").allowed()).isFalse();
    }

    /** A token the socket cannot prove is refused for its own reason, not this one. */
    @Test
    void anInvalidTokenIsRefusedBeforeTheRuleIsConsulted() throws Exception {
        HandshakeInterceptor chat = chatInterceptor();
        when(jwtUtil.extractUsername("tok")).thenReturn(null);

        assertThat(chat(chat, "/ws/chat", "tok").allowed()).isFalse();

        verify(guard, never()).mustChangePassword(anyString());
    }

    // ---------------------------------------------------------------- fixtures

    /** A token that passes the socket's own validation for the given address. */
    private void nexusToken(String token, String email) {
        when(jwtUtil.extractUsername(token)).thenReturn(email);
        when(jwtUtil.validateToken(token, email)).thenReturn(true);
        when(jwtUtil.validateToken(token, email, 0)).thenReturn(true);
    }

    /** The account the resolver finds, in company 3. */
    private void identity(String email) {
        identity(email, 0);
        }

        private void identity(String email, int tokenVersion) {
        when(principalResolver.resolve(email)).thenReturn(java.util.Optional.of(
            new SocketPrincipalResolver.Identity(9L, 3L, email, tokenVersion)));
    }

    private static User account(boolean mustChangePassword) {
        User user = new User("Ada", "ada@nexus.com", null, UserRole.MEMBER);
        user.setId(9L);
        user.setMustChangePassword(mustChangePassword);
        return user;
    }

    private HandshakeInterceptor chatInterceptor() {
        return interceptorOf(new ChatSocketConfig(
            mock(ChatSocketHandler.class), jwtUtil, supabaseTokenVerifier,
            supabaseUserService, supabaseProperties, guard, principalResolver));
    }

    private HandshakeInterceptor whiteboardInterceptor() {
        return interceptorOf(new WhiteboardSocketConfig(
            mock(WhiteboardSocketHandler.class), jwtUtil, supabaseTokenVerifier,
            supabaseUserService, supabaseProperties, guard, principalResolver));
    }

    /**
     * Registers the config against a recording registry and hands back the
     * interceptor it installed — the wiring as written, not a copy of the
     * decision under test.
     */
    private HandshakeInterceptor interceptorOf(WebSocketConfigurer config) {
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(any(WebSocketHandler.class), anyString())).thenReturn(registration);
        when(registration.addInterceptors(any(HandshakeInterceptor.class))).thenReturn(registration);

        config.registerWebSocketHandlers(registry);

        ArgumentCaptor<HandshakeInterceptor> captor = ArgumentCaptor.forClass(HandshakeInterceptor.class);
        verify(registration).addInterceptors(captor.capture());
        return captor.getValue();
    }

    /** Runs one handshake against a URL carrying the token, as a browser would. */
    private Outcome chat(HandshakeInterceptor interceptor, String path, String token) throws Exception {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("ws://localhost" + path + "?token=" + token));
        Map<String, Object> attributes = new HashMap<>();
        boolean allowed = interceptor.beforeHandshake(
            request, mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes);
        return new Outcome(allowed, attributes);
    }
}
