package com.nexus.backend.dto;

import com.nexus.backend.domain.user.UserRole;

/**
 * Result of an admin account creation or a password reset.
 *
 * <p>{@code password} is returned exactly once, at creation time, so the admin can
 * hand it over. It is never persisted in plain text and never returned again —
 * listing users deliberately does not include it.
 */
public record CreatedUserResponse(
    Long id,
    String name,
    String email,
    UserRole role,
    /** One-time generated or supplied password. Null if the admin supplied their own. */
    String password
) {}