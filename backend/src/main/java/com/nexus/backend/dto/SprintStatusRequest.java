package com.nexus.backend.dto;

import com.nexus.backend.domain.sprint.SprintStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Body for {@code PATCH /api/sprints/{id}/status}.
 *
 * Typed so Jackson validates the enum during binding: an unknown constant becomes
 * a 400 through the standard unreadable-body path. A raw {@code Map<String, String>}
 * with a manual {@code SprintStatus.valueOf(...)} instead threw an
 * IllegalArgumentException that surfaced as a 500.
 */
public record SprintStatusRequest(
    @NotNull(message = "Status is required")
    SprintStatus status
) {}