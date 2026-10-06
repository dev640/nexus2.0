package com.nexus.backend.dto;

import com.nexus.backend.domain.user.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Admin-only account creation. The password is optional: when omitted the server
 * generates a strong one and returns it in {@link CreatedUserResponse}.
 */
public record CreateUserRequest(
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    String name,

    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    String email,

    /**
     * Optional: a blank or absent password means "generate one for me". Only
     * honoured as a minimum length when supplied — admins are not forced to invent
     * a strong secret by hand.
     */
    @Size(min = 10, max = 200, message = "Password must be at least 10 characters")
    String password,

    /** Defaults to MEMBER when absent, so admins cannot accidentally self-promote by omission. */
    UserRole role
) {}