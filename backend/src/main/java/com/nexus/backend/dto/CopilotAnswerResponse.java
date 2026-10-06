package com.nexus.backend.dto;

/**
 * @param mode   "llm" when answered by the configured model, "grounded" when
 *               answered from live project data only
 * @param reason why the grounded answer was used, or null when {@code mode} is
 *               "llm". Safe to display: a short, fixed string that names the
 *               condition without exposing vendor errors, keys or internal
 *               detail. Null for a successful LLM answer.
 */
public record CopilotAnswerResponse(
    String answer,
    String mode,
    String reason
) {}