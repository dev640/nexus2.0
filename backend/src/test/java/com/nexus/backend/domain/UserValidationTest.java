package com.nexus.backend.domain;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entity-level constraints only. These run at persist time through Hibernate's
 * bean validation, which is why a mocked repository cannot catch them: a
 * password-less Supabase account must still be insertable (the column is
 * nullable since V8), while name and email stay required.
 */
class UserValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void aSupabaseProvisionedUserWithoutAPasswordIsValid() {
        User user = new User();
        user.setName("Ada Lovelace");
        user.setEmail("ada@nexus.com");
        user.setPassword(null);
        user.setRole(UserRole.MEMBER);
        user.setSupabaseId(UUID.randomUUID());

        assertThat(validator.validate(user)).isEmpty();
    }

    @Test
    void nameAndEmailAreStillRequired() {
        User user = new User();
        user.setPassword(null);

        Set<ConstraintViolation<User>> violations = validator.validate(user);

        assertThat(violations)
            .extracting(v -> v.getPropertyPath().toString())
            .contains("name", "email");
    }
}
