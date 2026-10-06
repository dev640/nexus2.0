package com.nexus.backend.dto;

import com.nexus.backend.domain.task.TaskPriority;
import com.nexus.backend.domain.task.TaskStatus;

import java.time.LocalDateTime;
import java.util.List;

public record TaskResponse(
    Long id,
    String title,
    String description,
    Long projectId,
    String projectName,
    Long sprintId,
    TaskStatus status,
    TaskPriority priority,
    Integer storyPoints,
    UserResponse assignee,
    List<String> labels,
    /** Waiting on something. Independent of status. */
    boolean blocked,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
