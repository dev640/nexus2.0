package com.nexus.backend.dto;

import com.nexus.backend.domain.project.ProjectStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectRequest(
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 200, message = "Name must be between 2 and 200 characters")
    String name,

    @Size(max = 1000, message = "Description cannot exceed 1000 characters")
    String description,

    ProjectStatus status
) {}
