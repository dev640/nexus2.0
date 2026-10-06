package com.nexus.backend.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private enum Status { TODO, DONE }

    /** Mirrors a real request record: an enum field is what makes the value invalid. */
    private record TaskBody(String title, Long projectId, Status status) {}

    /** Builds the exception Spring raises when Jackson cannot bind a body. */
    private HttpMessageNotReadableException unreadable(String json) {
        try {
            new ObjectMapper().readValue(json, TaskBody.class);
        } catch (InvalidFormatException e) {
            // The real cause is what Spring wraps, and what the handler inspects.
            return new HttpMessageNotReadableException("JSON parse error", e, null);
        } catch (Exception e) {
            throw new AssertionError("expected InvalidFormatException for: " + json, e);
        }
        throw new AssertionError("Jackson was expected to reject: " + json);
    }

    @Test
    void unknownEnumConstantIsABadRequestNotAServerError() {
        var ex = unreadable("{\"title\":\"x\",\"projectId\":1,\"status\":\"NOT_A_STATUS\"}");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleUnreadableBody(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().status()).isEqualTo(400);
        // The offending field is named so the caller can fix the request...
        assertThat(response.getBody().message()).contains("status");
        // ...but Jackson's message, which embeds internal class names, is not echoed.
        assertThat(response.getBody().message()).doesNotContain("com.nexus.backend");
    }

    @Test
    void wrongFieldTypeIsABadRequest() {
        var ex = unreadable("{\"title\":\"x\",\"projectId\":\"not-a-number\",\"status\":\"TODO\"}");

        var response = handler.handleUnreadableBody(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("projectId");
        assertThat(response.getBody().message()).doesNotContain("java.lang");
    }

    @Test
    void unreadableBodyWithoutAFieldPathStillGetsAGenericMessage() {
        // An empty body reaches the handler with no Jackson cause at all.
        var response = handler.handleUnreadableBody(
                new HttpMessageNotReadableException(
                        "Required request body is missing",
                        (org.springframework.http.HttpInputMessage) null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Malformed request body");
    }

    @Test
    void unexpectedExceptionDoesNotLeakItsMessage() {
        var response = handler.handleGenericException(
                new IllegalStateException("connection to jdbc:postgresql://db:5432 failed for user nexus"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        // Internal connection strings and class names must not reach the client.
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
    }
}