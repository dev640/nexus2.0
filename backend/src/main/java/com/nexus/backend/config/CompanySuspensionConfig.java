package com.nexus.backend.config;

import com.nexus.backend.service.CompanySocketCloser;
import com.nexus.backend.service.CompanySuspensionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Listens for company-suspension control messages published by whichever
 * instance handled the suspend request, and closes that company's sockets on
 * <em>this</em> one.
 *
 * <p>A suspension has to reach every instance, not just the one the admin's
 * request landed on: the other instances still hold live sockets for the
 * company's people, streaming the very traffic the row now refuses. The servlet
 * filter and the handshake read the row, so they refuse new work everywhere
 * already — this container is what ends the connections already open.
 *
 * <p>Its own bean rather than piggybacking on the whiteboard's listener, because
 * suspension is not a whiteboard concern and the two must not share a lifecycle.
 * Redis is optional here too: with none configured, a single-instance deployment
 * needs no cross-instance control at all and this runs as a no-op container,
 * mirroring the tolerance in {@code WhiteboardSocketConfig}.
 */
@Configuration
public class CompanySuspensionConfig {

    private static final Logger log = LoggerFactory.getLogger(CompanySuspensionConfig.class);

    /**
     * Subscribes to the suspension channel. The payload is the company id as a
     * decimal string; anything unparseable is logged and dropped rather than
     * closing an unknown or, worse, every company's sockets.
     */
    @Bean
    public RedisMessageListenerContainer companySuspensionRedisListener(
        ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
        CompanySocketCloser socketCloser
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setAutoStartup(false);
        RedisConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
        if (connectionFactory == null) {
            log.info("No Redis connection factory configured - suspension runs single-instance");
            return container;
        }
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(runnable -> {
            try {
                runnable.run();
            } catch (Exception e) {
                log.warn("Company-suspension Redis listener stopped: {}", e.getMessage());
            }
        });
        container.addMessageListener(
            (message, pattern) -> {
                try {
                    String payload = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                    Long companyId = Long.valueOf(payload.trim());
                    socketCloser.closeCompany(companyId);
                } catch (NumberFormatException e) {
                    log.warn("Ignoring suspension control with a non-numeric company id");
                } catch (Exception e) {
                    log.warn("Failed to apply suspension control from Redis: {}", e.getMessage());
                }
            },
            new ChannelTopic(CompanySuspensionService.CHANNEL)
        );
        try {
            container.start();
        } catch (Exception e) {
            log.warn("Redis suspension control unavailable at startup: {}", e.getMessage());
        }
        return container;
    }
}
