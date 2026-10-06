package com.nexus.backend.chat;

import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.security.JwtUtil;
import com.nexus.backend.security.SupabaseTokenVerifier;
import com.nexus.backend.service.SupabaseUserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
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
 */
@Configuration
public class ChatSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ChatSocketConfig.class);

    private final ChatSocketHandler handler;
    private final JwtUtil jwtUtil;
    private final SupabaseTokenVerifier supabaseTokenVerifier;
    private final SupabaseUserService supabaseUserService;
    private final SupabaseProperties supabaseProperties;

    public ChatSocketConfig(
        ChatSocketHandler handler,
        JwtUtil jwtUtil,
        SupabaseTokenVerifier supabaseTokenVerifier,
        SupabaseUserService supabaseUserService,
        SupabaseProperties supabaseProperties
    ) {
        this.handler = handler;
        this.jwtUtil = jwtUtil;
        this.supabaseTokenVerifier = supabaseTokenVerifier;
        this.supabaseUserService = supabaseUserService;
        this.supabaseProperties = supabaseProperties;
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
                    attributes.put("email", user.getEmail());
                    return true;
                } catch (JwtException | IllegalArgumentException e) {
                    log.debug("Supabase token rejected on chat handshake: {}", e.getMessage());
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
