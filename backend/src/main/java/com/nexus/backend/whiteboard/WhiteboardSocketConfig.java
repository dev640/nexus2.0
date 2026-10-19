package com.nexus.backend.whiteboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.backend.dto.WhiteboardEvent;
import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.security.JwtUtil;
import com.nexus.backend.security.MustChangePasswordHandshakeGuard;
import com.nexus.backend.security.SocketPrincipalResolver;
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
import java.util.Optional;

/**
 * Registers the whiteboard socket at /ws/whiteboard and authenticates the
 * handshake with a JWT passed as the `token` query parameter.
 *
 * <p>The handshake also enforces {@code must_change_password}, for the same
 * reason as the chat socket: see MustChangePasswordHandshakeGuard.
 */
@Configuration
@EnableWebSocket
public class WhiteboardSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardSocketConfig.class);

    /**
     * Session attributes the handler reads. Named here because the handshake
     * writes them and the handler is what depends on them: a handshake that
     * forgot one would leave the socket silently out of every broadcast, or
     * worse, in the wrong company's.
     */
    public static final String COMPANY_ATTRIBUTE = "companyId";
    public static final String USER_ATTRIBUTE = "userId";

    private final WhiteboardSocketHandler handler;
    private final JwtUtil jwtUtil;
    private final SupabaseTokenVerifier supabaseTokenVerifier;
    private final SupabaseUserService supabaseUserService;
    private final SupabaseProperties supabaseProperties;
    private final MustChangePasswordHandshakeGuard mustChangePasswordGuard;
    private final SocketPrincipalResolver principalResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WhiteboardSocketConfig(
        WhiteboardSocketHandler handler,
        JwtUtil jwtUtil,
        SupabaseTokenVerifier supabaseTokenVerifier,
        SupabaseUserService supabaseUserService,
        SupabaseProperties supabaseProperties,
        MustChangePasswordHandshakeGuard mustChangePasswordGuard,
        SocketPrincipalResolver principalResolver
    ) {
        this.handler = handler;
        this.jwtUtil = jwtUtil;
        this.supabaseTokenVerifier = supabaseTokenVerifier;
        this.supabaseUserService = supabaseUserService;
        this.supabaseProperties = supabaseProperties;
        this.mustChangePasswordGuard = mustChangePasswordGuard;
        this.principalResolver = principalResolver;
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
                    // A socket relays the same notes the account's HTTP requests
                    // are refused, so the gate applies here too.
                    if (mustChangePasswordGuard.mustChangePassword(email)) {
                        log.debug("Whiteboard handshake refused: account must change its password first");
                        return false;
                    }
                    attributes.put("email", email);
                    return stampIdentity(email, token, attributes);
                }
            } catch (Exception e) {
                log.debug("Not a Nexus token on whiteboard handshake: {}", e.getMessage());
            }

            // Supabase tokens (subject = UUID) when the integration is configured.
            if (supabaseProperties.isEnabled()) {
                try {
                    Claims claims = supabaseTokenVerifier.verify(token);
                    User user = supabaseUserService.resolveUser(claims);
                    // The account is already in hand, so the rule is read from it
                    // rather than looked up again.
                    if (user.isMustChangePassword()) {
                        log.debug("Whiteboard handshake refused: account must change its password first");
                        return false;
                    }
                    attributes.put("email", user.getEmail());
                    // The account is already in hand, so its company is read from
                    // it rather than looked up again.
                    return stampCompany(user.getCompanyId(), user.getId(), attributes);
                } catch (JwtException | IllegalArgumentException e) {
                    log.debug("Supabase token rejected on whiteboard handshake: {}", e.getMessage());
                }
            }

            log.debug("Whiteboard handshake rejected: no valid token");
            return false;
        }

        /**
         * Records which company the socket belongs to. Refuses the handshake when
         * the address has no company to record: an operator account, or one the
         * database does not know. A socket that cannot be attributed would have
         * to be left out of every broadcast - so the connection is not opened.
         */
        private boolean stampIdentity(String email, String token, Map<String, Object> attributes) {
            Optional<SocketPrincipalResolver.Identity> identity = principalResolver.resolve(email);
            if (identity.isEmpty()) {
                log.debug("Whiteboard handshake refused: no company for {}", email);
                return false;
            }
            if (!jwtUtil.validateToken(token, email, identity.get().tokenVersion())) {
                log.debug("Whiteboard handshake refused: stale account token version for {}", email);
                return false;
            }
            return stampCompany(identity.get().companyId(), identity.get().userId(), attributes);
        }

        /** The one place the company and user attributes are written. */
        private boolean stampCompany(Long companyId, Long userId, Map<String, Object> attributes) {
            if (companyId == null) {
                log.debug("Whiteboard handshake refused: account belongs to no company");
                return false;
            }
            attributes.put(COMPANY_ATTRIBUTE, companyId);
            attributes.put(USER_ATTRIBUTE, userId);
            return true;
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
