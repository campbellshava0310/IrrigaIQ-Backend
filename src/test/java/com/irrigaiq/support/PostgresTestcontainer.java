package com.irrigaiq.support;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

/**
 * Same image as compose.yaml (PostgreSQL 18 with pgvector), so migrations are tested against
 * production SQL.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer(
				DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));
	}

}
