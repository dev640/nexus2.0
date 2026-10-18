package com.nexus.backend.dto;

import com.nexus.backend.domain.user.UserRole;

public record UserResponse(
    Long id,
    String name,
    String email,
    UserRole role,
    String supabaseId,
    /** Human-facing employee code (NX-0007). Avatar bytes are never sent. */
    String employeeCode,
    /**
     * True when the account is still on a password an admin issued.
     *
     * <p>Sent to the client because the client is what renders the forced
     * change screen, and it has to know before it can draw anything else. It is
     * not the enforcement — {@code MustChangePasswordFilter} is, server-side, for
     * every endpoint but the change itself.
     */
    boolean mustChangePassword
) {}
