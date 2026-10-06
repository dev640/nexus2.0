package com.nexus.backend.dto;

import com.nexus.backend.domain.task.TaskPriority;
import com.nexus.backend.domain.task.TaskStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record TaskRequest(
    @NotBlank(message = "Title is required")
    String title,

    String description,

    @NotNull(message = "Project ID is required")
    Long projectId,

    Long sprintId,

    @NotNull(message = "Status is required")
    TaskStatus status,

    @NotNull(message = "Priority is required")
    TaskPriority priority,

    @Min(value = 0, message = "Story points must be non-negative")
    Integer storyPoints,

    Long assigneeId,

    List<String> labels,

    /** Null leaves the existing value alone on update; defaults to false on create. */
    Boolean blocked
) {}
