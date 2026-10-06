package com.nexus.backend.dto;

import com.nexus.backend.domain.user.ResetRequestStatus;

import java.time.LocalDateTime;

/** A password-reset request as the admin queue sees it. */
public record PasswordResetResponse(
    Long id,
    Long userId,
    String userName,
    String userEmail,
    ResetRequestStatus status,
    LocalDateTime requestedAt,
    LocalDateTime resolvedAt,
    String resolvedByName,
    String note
) {}