package com.datum.clientmanagement;

import com.github.dockerjava.api.DockerClient;
import org.awaitility.Awaitility;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.AbstractConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.time.Duration;

/** Stops and restarts the RabbitMQ container of {@link RestartableRabbitConfiguration} (same container, same port). */
public class RabbitOutage {

	private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);
	private static final Duration READY_POLL_INTERVAL = Duration.ofMillis(250);

	private final RabbitMQContainer container;
	private final ConnectionFactory connectionFactory;
	private final DockerClient docker = DockerClientFactory.instance().client();

	RabbitOutage(RabbitMQContainer container, ConnectionFactory connectionFactory) {
		this.container = container;
		this.connectionFactory = connectionFactory;
	}

	public void stop() {
		docker.stopContainerCmd(container.getContainerId()).exec();
		// Drop the app's cached connection now instead of on its first failed use.
		if (connectionFactory instanceof AbstractConnectionFactory cached)
			cached.resetConnection();
	}

	/** Starts the container again and returns at once; RabbitMQ needs a few more seconds to accept connections. */
	public void start() {
		docker.startContainerCmd(container.getContainerId()).exec();
	}

	/**
	 * Freezes the broker: the app's TCP connection stays open and writes still succeed, but RabbitMQ answers nothing
	 * (no confirms) until {@link #unpause()}.
	 */
	public void pause() {
		docker.pauseContainerCmd(container.getContainerId()).exec();
	}

	public void unpause() {
		docker.unpauseContainerCmd(container.getContainerId()).exec();
	}

	public boolean isRunning() {
		return Boolean.TRUE.equals(docker.inspectContainerCmd(container.getContainerId()).exec().getState().getRunning());
	}

	/** Blocks until the app can open an AMQP connection again. */
	public void awaitReady() {
		Awaitility.await()
				.atMost(READY_TIMEOUT)
				.pollInterval(READY_POLL_INTERVAL)
				.ignoreExceptionsInstanceOf(AmqpException.class)
				.until(this::canConnect);
	}

	private boolean canConnect() {
		try (Connection connection = connectionFactory.createConnection()) {
			return connection.isOpen();
		}
	}
}
