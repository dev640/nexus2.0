package com.nexus.backend.dto;

import java.time.LocalDateTime;

/**
 * One company as the operator sees it: identity, suspension state, and how many
 * accounts it holds.
 *
 * <p>The status is what {@code POST /api/admin/companies/{id}/suspend} flips, and
 * the count is what that action locks out — an operator weighing a suspension
 * needs both on the same line. Returned only from a SUPER_ADMIN endpoint; never
 * from a tenant-scoped one.
 */
public record CompanyAdminResponse(
    Long id,
    String name,
    String status,
    /** Accounts in this company; 0 for a company nobody has joined yet. */
    Long employeeCount,
    LocalDateTime createdAt
) {}
