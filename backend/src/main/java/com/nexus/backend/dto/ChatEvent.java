package com.nexus.backend.dto;

import java.util.List;

/** Real-time chat event pushed over WebSocket. Type selects the payload kind. */
public record ChatEvent(
    String type,
    Long channelId,
    ChatMessageResponse message,
    List<ReactionEvent> reactions,
    Long userId,
    String userName,
    String channelName,
    Long messageId
) {
    public static final String MESSAGE_CREATED = "message.created";
    public static final String MESSAGE_UPDATED = "message.updated";
    public static final String MESSAGE_DELETED = "message.deleted";
    public static final String REACTIONS_UPDATED = "reactions.updated";
    public static final String CHANNEL_CREATED = "channel.created";
    public static final String CHANNEL_DELETED = "channel.deleted";
    public static final String TYPING = "typing";
    public static final String PRESENCE = "presence";

    public record ReactionEvent(Long messageId, String emoji, List<Long> userIds) {}
}
