package com.nexus.backend.whiteboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.backend.dto.WhiteboardEvent;
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
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.context.annotation.Bean;

import java.net.URI;
import java.util.Map;

/**
 * Registers the whiteboard socket at /ws/whiteboard and authenticates the
 * handshake with a JWT passed as the `token` query parameter.
 */
@Configuration
@EnableWebSocket
public class WhiteboardSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardSocketConfig.class);

    private final WhiteboardSocketHandler handler;
    private final JwtUtil jwtUtil;
    private final SupabaseTokenVerifier supabaseTokenVerifier;
    private final SupabaseUserService supabaseUserService;
    private final SupabaseProperties supabaseProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WhiteboardSocketConfig(
        WhiteboardSocketHandler handler,
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
        registry.addHandler(handler, "/ws/whiteboard")
            .addInterceptors(new JwtHandshakeInterceptor())
            .setAllowedOriginPatterns("*");
    }

    /** Rejects handshakes without a valid token. */
    private class JwtHandshakeInterceptor implements HandshakeInterceptor {
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
                log.debug("Whiteboard handshake rejected: missing token");
                return false;
            }
            try {
                String email = jwtUtil.extractUsername(token);
                if (email != null && email.contains("@") && jwtUtil.validateToken(token, email)) {
                    attributes.put("email", email);
                    return true;
                }
            } catch (Exception e) {
                log.debug("Not a Nexus token on whiteboard handshake: {}", e.getMessage());
            }

            // Supabase tokens (subject = UUID) when the integration is configured.
            if (supabaseProperties.isEnabled()) {
                try {
                    Claims claims = supabaseTokenVerifier.verify(token);
                    User user = supabaseUserService.resolveUser(claims);
                    attributes.put("email", user.getEmail());
                    return true;
                } catch (JwtException | IllegalArgumentException e) {
                    log.debug("Supabase token rejected on whiteboard handshake: {}", e.getMessage());
                }
            }

            log.debug("Whiteboard handshake rejected: no valid token");
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

    /**
     * Relays events published by other instances to this instance's clients.
     *
     * Redis is optional: a single-instance deployment works with no Redis at all. This
     * container is started lazily and must not fail the application when Redis is absent
     * or unreachable (a managed Redis plugin that is still provisioning, for example) —
     * in that case the app runs without cross-instance fan-out and retries on restart.
     */
    @Bean
    public RedisMessageListenerContainer whiteboardRedisListener(
        org.springframework.beans.factory.ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
        WhiteboardBroadcaster broadcaster
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setAutoStartup(false);
        RedisConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
        if (connectionFactory == null) {
            log.info("No Redis connection factory configured - whiteboard runs single-instance");
            return container;
        }
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(runnable -> {
            try {
                runnable.run();
            } catch (Exception e) {
                log.warn("Whiteboard Redis listener stopped (cross-instance fan-out disabled): {}", e.getMessage());
            }
        });
        container.addMessageListener(
            (message, pattern) -> {
                try {
                    WhiteboardEvent event = objectMapper.readValue(message.getBody(), WhiteboardEvent.class);
                    // Skip our own echo: this instance already delivered it locally.
                    if (!broadcaster.isOwnEvent(event)) {
                        broadcaster.deliver(event);
                    }
                } catch (Exception e) {
                    log.warn("Ignoring malformed whiteboard event from Redis: {}", e.getMessage());
                }
            },
            new ChannelTopic(WhiteboardBroadcaster.CHANNEL)
        );
        // Start asynchronously: a missing/unreachable Redis logs a warning instead of
        // aborting application startup.
        try {
            container.start();
        } catch (Exception e) {
            log.warn("Redis fan-out unavailable at startup (whiteboard runs single-instance): {}", e.getMessage());
        }
        return container;
    }
}
