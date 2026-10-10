package com.nexus.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ErrorProperties.IncludeAttribute;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sliced web tests never bind the server error switches, so an illegal value
 * in {@code application.properties} would only surface when the real application
 * starts. These checks keep the checked-in defaults bindable.
 */
class ApplicationPropertiesTest {

    private static Properties classpathProperties() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(in);
        }
        return properties;
    }

    /** The part after the last ':' inside {@code ${VAR:default}}; empty when unset. */
    private static String fallbackOf(String configured) {
        String value = configured.trim();
        if (value.startsWith("${") && value.endsWith("}")) {
            value = value.substring(2, value.length() - 1);
            int separator = value.lastIndexOf(':');
            return separator < 0 ? "" : value.substring(separator + 1);
        }
        return value;
    }

    @Test
    void errorInclusionDefaultsAreLegalValues() throws Exception {
        Properties properties = classpathProperties();
        Set<String> legal = Arrays.stream(IncludeAttribute.values())
            .map(value -> value.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());

        for (String key : new String[] {
                "server.error.include-message", "server.error.include-binding-errors" }) {
            String configured = properties.getProperty(key);
            assertThat(configured).as(key).isNotNull();
            assertThat(legal).as(key)
                .contains(fallbackOf(configured).toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void theTunablesReadByFiltersAndHandlersAreDeclared() throws Exception {
        Properties properties = classpathProperties();

        for (String key : new String[] {
                "nexus.cors.allowed-origins",
                "security.headers.hsts-max-age-seconds",
                "security.headers.content-security-policy",
                "nexus.global.rate-limit.max-requests",
                "nexus.global.rate-limit.window-seconds",
                "nexus.api.max-request-size-bytes",
                "nexus.ws.max-text-message-bytes",
                "nexus.ws.max-messages-per-window",
                "nexus.ws.message-window-seconds" }) {
            // A typo here is silent: @Value falls back to the code default and the
            // configuration knob does nothing at all.
            assertThat(properties).as(key).containsKey(key);
        }
    }

    @Test
    void jwtSecretShipsNoDefaultValue() throws Exception {
        String configured = classpathProperties().getProperty("jwt.secret");

        assertThat(configured).isNotNull();
        assertThat(fallbackOf(configured))
            .as("a shipped fallback secret would let anyone mint tokens for a deployment that forgot to set one")
            .isEmpty();
    }
}
