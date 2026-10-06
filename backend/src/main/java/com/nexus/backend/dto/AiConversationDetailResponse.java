package com.nexus.backend.dto;

import java.util.List;

/** A conversation with its full, ordered message history. */
public record AiConversationDetailResponse(
    AiConversationResponse conversation,
    List<AiMessageResponse> messages
) {}
