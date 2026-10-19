package com.nexus.backend.dto;

import com.nexus.backend.domain.user.UserRole;

/**
 * Result of company provisioning. The generated password is returned exactly
 * once and is never stored or available from a later company listing.
 */
public record CreatedCompanyResponse(
    Long companyId,
    String companyName,
    Long adminId,
    String adminName,
    String adminEmail,
    UserRole role,
    String generatedPassword
) {}
