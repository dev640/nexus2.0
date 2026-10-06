package com.nexus.backend;

import com.nexus.backend.config.PlatformEnvironment;
import com.nexus.backend.config.SupabaseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(SupabaseProperties.class)
// JPA auditing is enabled in JpaAuditingConfig, not here: web-layer test slices
// have no JPA context and cannot satisfy a jpaMappingContext.
@EnableScheduling // periodic vector-index refresh for the Nexus AI assistant
public class BackendApplication {

	public static void main(String[] args) {
		// Translate platform connection strings (DATABASE_URL, REDIS_URL) into the
		// Spring properties the application reads, before the context starts.
		PlatformEnvironment.apply();
		SpringApplication.run(BackendApplication.class, args);
	}

}
