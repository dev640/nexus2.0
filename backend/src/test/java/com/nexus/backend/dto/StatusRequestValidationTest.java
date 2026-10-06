package com.nexus.backend.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The status endpoints used to take a raw Map and call Enum.valueOf by hand, so an
 * unknown constant threw IllegalArgumentException and surfaced as a 500. These pin
 * that the typed bodies make Jackson reject the value during binding instead, which
 * the unreadable-body handler turns into a 400.
 */
class StatusRequestValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validSprintStatusBinds() throws Exception {
        var req = mapper.readValue("{\"status\":\"ACTIVE\"}", SprintStatusRequest.class);
        assertThat(req.status().name()).isEqualTo("ACTIVE");
    }

    @Test
    void validTaskStatusBinds() throws Exception {
        var req = mapper.readValue("{\"status\":\"IN_PROGRESS\"}", TaskStatusRequest.class);
        assertThat(req.status().name()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void unknownSprintStatusIsRejectedByJackson() {
        assertThatThrownBy(() -> mapper.readValue("{\"status\":\"CANCELLED\"}", SprintStatusRequest.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void unknownTaskStatusIsRejectedByJackson() {
        assertThatThrownBy(() -> mapper.readValue("{\"status\":\"NOT_A_STATUS\"}", TaskStatusRequest.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void missingStatusBindsToNullAndIsRejectedByBeanValidation() throws Exception {
        // Jackson maps an absent field to null rather than failing, so @NotNull on
        // the record is what turns it into a 400 once @Valid runs in the controller.
        var req = mapper.readValue("{}", SprintStatusRequest.class);
        assertThat(req.status()).isNull();

        var violations = Validation.buildDefaultValidatorFactory().getValidator().validate(req);
        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("status");
    }
}