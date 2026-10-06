package com.nexus.backend;

import com.nexus.backend.config.PlatformEnvironment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling // periodic vector-index refresh for the Nexus AI assistant
public class BackendApplication {

	public static void main(String[] args) {
		// Translate platform connection strings (DATABASE_URL, REDIS_URL) into the
		// Spring properties the application reads, before the context starts.
		PlatformEnvironment.apply();
		SpringApplication.run(BackendApplication.class, args);
	}

}
