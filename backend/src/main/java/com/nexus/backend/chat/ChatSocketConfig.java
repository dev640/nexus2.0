package com.nexus.backend.chat;

import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.security.JwtUtil;
import com.nexus.backend.security.MustChangePasswordHandshakeGuard;
import com.nexus.backend.security.SupabaseTokenVerifier;
import com.nexus.backend.service.SupabaseUserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * Registers the chat socket at /ws/chat and authenticates the handshake with a
 * JWT passed as the `token` query parameter — the same rule as the whiteboard.
 *
 * <p>The handshake also enforces {@code must_change_password}: a socket hands
 * out chat traffic, so an account that is refused every HTTP request would
 * otherwise still read the channel here. The gate cannot be the servlet filter's
 * for this request — see MustChangePasswordHandshakeGuard.
 */
@Configuration
public class ChatSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ChatSocketConfig.class);

    private final ChatSocketHandler handler;
    private final JwtUtil jwtUtil;
    private final SupabaseTokenVerifier supabaseTokenVerifier;
    private final SupabaseUserService supabaseUserService;
    private final SupabaseProperties supabaseProperties;
    private final MustChangePasswordHandshakeGuard mustChangePasswordGuard;

    public ChatSocketConfig(
        ChatSocketHandler handler,
        JwtUtil jwtUtil,
        SupabaseTokenVerifier supabaseTokenVerifier,
        SupabaseUserService supabaseUserService,
        SupabaseProperties supabaseProperties,
        MustChangePasswordHandshakeGuard mustChangePasswordGuard
    ) {
        this.handler = handler;
        this.jwtUtil = jwtUtil;
        this.supabaseTokenVerifier = supabaseTokenVerifier;
        this.supabaseUserService = supabaseUserService;
        this.supabaseProperties = supabaseProperties;
        this.mustChangePasswordGuard = mustChangePasswordGuard;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/chat")
            .addInterceptors(new ChatHandshakeInterceptor())
            .setAllowedOriginPatterns("*");
    }

    /** Rejects handshakes without a valid Nexus or Supabase token. */
    private class ChatHandshakeInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
        ) {
            URI uri = request.getURI();
            String query = uri.getQuery();
            String token = null;
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("token=")) {
                        token = part.substring("token=".length());
                    }
                }
            }
            if (token == null || token.isBlank()) {
                log.debug("Chat handshake rejected: missing token");
                return false;
            }
            try {
                String email = jwtUtil.extractUsername(token);
                if (email != null && email.contains("@") && jwtUtil.validateToken(token, email)) {
                    if (mustChangePasswordGuard.mustChangePassword(email)) {
                        log.debug("Chat handshake refused: account must change its password first");
                        return false;
                    }
                    attributes.put("email", email);
                    return true;
                }
            } catch (Exception e) {
                log.debug("Not a Nexus token on chat handshake: {}", e.getMessage());
            }

            if (supabaseProperties.isEnabled()) {
                try {
                    Claims claims = supabaseTokenVerifier.verify(token);
                    User user = supabaseUserService.resolveUser(claims);
                    // The account is already in hand, so the rule is read from it
                    // rather than looked up again.
                    if (user.isMustChangePassword()) {
                        log.debug("Chat handshake refused: account must change its password first");
                        return false;
                    }
                    attributes.put("email", user.getEmail());
                    return true;
                } catch (JwtException | IllegalArgumentException e) {
                    log.debug("Supabase token rejected on chat handshake: {}", e.getMessage());
                } catch (UsernameNotFoundException e) {
                    // A valid Supabase identity with no account here. Reject the
                    // handshake instead of letting it become a 500: the answer is
                    // that this person may not open a socket in this workspace.
                    log.debug("Supabase identity has no local account: {}", e.getMessage());
                }
            }

            log.debug("Chat handshake rejected: no valid token");
            return false;
        }

        @Override
        public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
        ) {
            // no-op
        }
    }
}
