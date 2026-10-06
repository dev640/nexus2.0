package com.nexus.backend.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wiki creates an empty page and then opens the editor, so blank content must
 * be accepted. It was @NotBlank, which made every "New Page" submission fail.
 */
class WikiPageRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> propertiesFor(WikiPageRequest request) {
        return validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void blankContentIsAccepted() {
        assertThat(propertiesFor(new WikiPageRequest("Runbook", "", 1L))).isEmpty();
    }

    @Test
    void nullContentIsAccepted() {
        assertThat(propertiesFor(new WikiPageRequest("Runbook", null, null))).isEmpty();
    }

    @Test
    void contentIsStillPersistedWhenSupplied() {
        assertThat(propertiesFor(new WikiPageRequest("Runbook", "real content", null))).isEmpty();
    }

    @Test
    void titleIsStillRequired() {
        assertThat(propertiesFor(new WikiPageRequest("", "", null))).contains("title");
        assertThat(propertiesFor(new WikiPageRequest("   ", "body", null))).contains("title");
    }

    @Test
    void titleLengthIsStillCapped() {
        String tooLong = "x".repeat(201);
        assertThat(propertiesFor(new WikiPageRequest(tooLong, "body", null))).contains("title");
    }
}