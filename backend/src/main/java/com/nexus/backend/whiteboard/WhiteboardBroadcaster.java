package com.nexus.backend.whiteboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.backend.dto.WhiteboardEvent;
import com.nexus.backend.dto.WhiteboardNoteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fans note events out to the clients of one company.
 *
 * <p>That company's local WebSocket sessions are always notified. When Redis is
 * reachable the event is also published on a channel so other backend instances
 * (each of which subscribes to {@link #CHANNEL}) relay it to their own clients -
 * and every instance applies the same company filter on the way out, because the
 * event has to say whose it is before it can be delivered at all.
 */
@Component
public class WhiteboardBroadcaster {

    public static final String CHANNEL = "nexus:whiteboard";

    private static final Logger log = LoggerFactory.getLogger(WhiteboardBroadcaster.class);

    private final WhiteboardSocketHandler socketHandler;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String instanceId = java.util.UUID.randomUUID().toString();

    public WhiteboardBroadcaster(
        WhiteboardSocketHandler socketHandler,
        ObjectProvider<StringRedisTemplate> redisTemplateProvider
    ) {
        this.socketHandler = socketHandler;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Publish an event: locally always, plus Redis so other instances relay it.
     *
     * <p>The company is required. An event that cannot name whose board it
     * describes has no audience that can be filtered, and the alternative to
     * refusing it is delivering one company's note to all of them.
     */
    public void publish(String type, WhiteboardNoteResponse note, Long companyId) {
        if (companyId == null) {
            log.warn("Not publishing a whiteboard event that names no company");
            return;
        }
        WhiteboardEvent event = new WhiteboardEvent(type, note, companyId, instanceId);
        deliver(event);
        if (redisTemplate == null) return;
        try {
            redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            // Redis is optional: real-time still works within this instance.
            log.debug("Redis fan-out unavailable, continuing locally: {}", e.getMessage());
        }
    }

    /** True when this event was produced by this instance (already delivered locally). */
    public boolean isOwnEvent(WhiteboardEvent event) {
        return instanceId.equals(event.origin());
    }

    /**
     * Deliver an event to this instance's connected sessions.
     *
     * <p>Applies to events that arrived from Redis as much as to local ones: the
     * publishing instance is a different process, so its scoping is not evidence
     * about what this one should send.
     */
    public void deliver(WhiteboardEvent event) {
        if (event == null || event.companyId() == null) {
            log.warn("Dropping a whiteboard event that names no company");
            return;
        }
        try {
            socketHandler.broadcast(objectMapper.writeValueAsString(event), event.companyId());
        } catch (Exception e) {
            log.warn("Failed to broadcast whiteboard event: {}", e.getMessage());
        }
    }
}
