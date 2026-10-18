package com.nexus.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Event broadcast to the connected clients of one company.
 *
 * @param companyId whose board this event belongs to. Load-bearing: the
 *                  broadcasting instance filters on it, and so does every other
 *                  instance that receives the event from Redis. An event that
 *                  arrives without it is dropped rather than delivered, because
 *                  the only audience that could be assumed is everybody's.
 * @param origin    id of the instance that produced the event, so the producing
 *                  instance can ignore its own Redis echo (it already delivered
 *                  locally)
 */
public record WhiteboardEvent(
    @NotBlank String type,
    @NotNull WhiteboardNoteResponse note,
    @NotNull Long companyId,
    String origin
) {}
