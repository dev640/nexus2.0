package com.nexus.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A signed-in user replacing their own password.
 *
 * <p>The current password is required even though the caller is already
 * authenticated. An access token is a bearer credential: anyone who steals one
 * could otherwise set a new password and lock the owner out of their own
 * account. Re-proving the existing password is what keeps a stolen token from
 * becoming a stolen account.
 *
 * <p>The length rule deliberately matches {@code RegisterRequest}: a password
 * that would be accepted at registration but rejected on change (or the reverse)
 * is a rule nobody can rely on.
 */
public record ChangePasswordRequest(
    @NotBlank(message = "Current password is required")
    String currentPassword,

    @NotBlank(message = "New password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    String newPassword
) {}
