package com.nexus.backend.dto;

import com.nexus.backend.domain.user.UserRole;

/**
 * One account across all companies, as the operator sees it: the person's
 * identity plus the company it belongs to and that company's state.
 *
 * <p>The company fields are the point of a cross-company listing — an account
 * with no company shown would be a flat list nobody could act on, and the
 * suspension status lets an operator see at a glance which accounts are locked
 * out. Returned only from a SUPER_ADMIN endpoint.
 *
 * <p>Never carries the password hash or avatar bytes: those stay server-side
 * like every other account response.
 */
public record AdminAccountResponse(
    Long id,
    String name,
    String email,
    UserRole role,
    String employeeCode,
    boolean mustChangePassword,
    Long companyId,
    String companyName,
    /** ACTIVE or SUSPENDED — the company's state, so locked-out accounts are visible. */
    String companyStatus
) {}
