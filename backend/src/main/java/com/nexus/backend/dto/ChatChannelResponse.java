package com.nexus.backend.dto;

import com.nexus.backend.domain.chat.ChatChannel;

import java.time.LocalDateTime;

/**
 * A conversation in the sidebar. For DMs the display name is the partner's
 * name and id is the partner's user id, so the client can open conversations
 * by person without knowing internal channel ids.
 */
public record ChatChannelResponse(
    Long id,
    String name,
    String type,
    String topic,
    boolean member,
    Long partnerId,
    String partnerName,
    /**
     * Email of whoever created the channel. Exposed so the client can offer
     * "delete channel" only to the creator or an admin, instead of showing a
     * button that is guaranteed to be refused.
     */
    String createdBy,
    LocalDateTime createdAt
) {
    public static ChatChannelResponse of(ChatChannel c, boolean member, Long partnerId, String partnerName) {
        return new ChatChannelResponse(
            c.getId(),
            c.getName(),
            c.getType().name(),
            c.getTopic(),
            member,
            partnerId,
            partnerName,
            c.getCreatedBy(),
            c.getCreatedAt()
        );
    }
}
