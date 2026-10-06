package com.nexus.backend.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.nexus.backend.service.ai.AiEngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
            HttpStatus.NOT_FOUND.value(),
            ex.getMessage(),
            LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationException ex) {
        ErrorResponse error = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            ex.getMessage(),
            LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        ErrorResponse error = new ErrorResponse(
            HttpStatus.FORBIDDEN.value(),
            "You do not have permission to perform this action",
            LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler({AuthenticationException.class, AuthenticationCredentialsNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        ErrorResponse error = new ErrorResponse(
            HttpStatus.UNAUTHORIZED.value(),
            "Authentication required",
            LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

/**
     * AI engine failures carry an already-safe, user-facing message, so it is
     * surfaced as-is; only the HTTP status is chosen here from the failure kind.
     */
    @ExceptionHandler(AiEngineException.class)
    public ResponseEntity<ErrorResponse> handleAiEngine(AiEngineException ex) {
        HttpStatus status = switch (ex.kind()) {
            case RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        ErrorResponse error = new ErrorResponse(
            status.value(),
            ex.userMessage(),
            LocalDateTime.now()
        );
        return ResponseEntity.status(status).body(error);
    }

    /**
     * An unreadable body is the caller's mistake — malformed JSON, an unknown enum
     * constant or a field of the wrong type — so it is a 400, not a 500. Jackson's
     * message names internal classes and packages, so only the offending field name
     * is echoed back; the raw cause is logged instead.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        String field = unreadableField(ex);
        log.debug("Unreadable request body (field={}): {}", field, ex.getMessage());
        ErrorResponse error = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            field == null
                ? "Malformed request body"
                : "Invalid value for field \"" + field + "\"",
            LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /** Best-effort field name from the Jackson cause, or null when unavailable. */
    private String unreadableField(HttpMessageNotReadableException ex) {
        if (!(ex.getCause() instanceof InvalidFormatException cause)) return null;
        if (cause.getPath() == null || cause.getPath().isEmpty()) return null;
        return cause.getPath().get(cause.getPath().size() - 1).getFieldName();
    }

    /**
     * An oversized upload is rejected by the servlet container before any handler
     * runs. That is the caller's mistake, not a server fault, so it is a 400
     * with the same wording the service uses for its own size check — otherwise
     * an upload just over the limit would 500 and a much larger one would 400
     * with two different messages for one rule.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        ErrorResponse error = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            "Avatar must be smaller than 256 KB",
            LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        // Log the cause with its stack trace: without this an unexpected 500 leaves
        // no trace in the logs. The response stays generic because ex.getMessage()
        // can carry internal class names, SQL or paths.
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        ErrorResponse error = new ErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "An unexpected error occurred",
            LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    public record ErrorResponse(
        int status,
        String message,
        LocalDateTime timestamp
    ) {}
}
