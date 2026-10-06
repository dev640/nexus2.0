package com.nexus.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WikiPageRequest(
    @NotBlank(message = "Title is required")
    @Size(max = 200, message = "Title must not exceed 200 characters")
    String title,
    // Content may be blank: the wiki creates an empty page and then opens the
    // editor, and the reader view has an explicit "this page is empty" state.
    // Requiring it here made every "New Page" submission fail with a 400.
    String content,
    Long projectId
) {}
