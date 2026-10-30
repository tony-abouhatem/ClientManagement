package com.datum.clientmanagement;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.util.List;

/**
 * Like {@link TestcontainersConfiguration}, but RabbitMQ can be stopped and started again to simulate an outage
 * ({@link RabbitOutage}). Its AMQP port is bound to a fixed host port, because Docker would otherwise pick a new
 * random port on restart and the app could never reconnect.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RestartableRabbitConfiguration {

	private static final int AMQP_PORT = 5672;

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);
	}

	@Bean
	@ServiceConnection
	RabbitMQContainer rabbitContainer() {
		RabbitMQContainer container = new RabbitMQContainer(TestcontainersConfiguration.RABBITMQ_IMAGE);
		container.setPortBindings(List.of(TestPorts.freePort() + ":" + AMQP_PORT));
		return container;
	}

	@Bean
	RabbitOutage rabbitOutage(RabbitMQContainer rabbitContainer, ConnectionFactory connectionFactory) {
		return new RabbitOutage(rabbitContainer, connectionFactory);
	}
}
