package com.nexus.backend.dto;

import com.nexus.backend.domain.chat.ChatMessage;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** One chat message with its reaction summary. */
public record ChatMessageResponse(
    Long id,
    Long channelId,
    Long authorId,
    String authorName,
    String body,
    boolean edited,
    Map<String, java.util.List<Long>> reactions,
    LocalDateTime createdAt
) {
    public static ChatMessageResponse of(ChatMessage m, Map<String, java.util.List<Long>> reactions) {
        return new ChatMessageResponse(
            m.getId(),
            m.getChannel() != null ? m.getChannel().getId() : null,
            m.getAuthor() != null ? m.getAuthor().getId() : null,
            m.getAuthor() != null ? m.getAuthor().getName() : null,
            m.getBody(),
            m.isEdited(),
            reactions,
            m.getCreatedAt()
        );
    }

    public static ChatMessageResponse of(ChatMessage m) {
        return of(m, new LinkedHashMap<>());
    }
}
