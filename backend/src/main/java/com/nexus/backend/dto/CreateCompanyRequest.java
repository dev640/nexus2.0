package com.nexus.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** SUPER_ADMIN request to create a company and its first administrator. */
public record CreateCompanyRequest(
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 200, message = "Name must be between 2 and 200 characters")
    String name,

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    String adminName,

    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    String adminEmail
) {}
