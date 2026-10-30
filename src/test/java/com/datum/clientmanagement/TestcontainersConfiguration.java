package com.datum.clientmanagement;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL and RabbitMQ for integration tests, wired into Spring through @ServiceConnection.
 * Image versions match docker-compose.yml so tests run against the same engines as the local stack.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");
	public static final DockerImageName RABBITMQ_IMAGE = DockerImageName.parse("rabbitmq:4.1-management-alpine");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(POSTGRES_IMAGE);
	}

	@Bean
	@ServiceConnection
	RabbitMQContainer rabbitContainer() {
		return new RabbitMQContainer(RABBITMQ_IMAGE);
	}
}
