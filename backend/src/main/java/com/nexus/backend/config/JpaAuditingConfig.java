package com.nexus.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA auditing (created/updated timestamps) lives here rather than on the
 * application class so web-layer test slices, which have no JPA context, do
 * not pull in a jpaMappingContext they cannot satisfy.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
