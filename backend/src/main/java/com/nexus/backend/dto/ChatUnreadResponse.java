package com.nexus.backend.dto;

import java.time.LocalDateTime;

/** Unread summary for the sidebar badges: per-channel counts plus a total. */
public record ChatUnreadResponse(
    long total,
    java.util.List<ChannelUnread> channels
) {
    public record ChannelUnread(
        Long channelId,
        String type,
        Long partnerId,
        long count
    ) {}
}
