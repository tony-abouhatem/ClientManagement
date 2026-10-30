package com.datum.clientmanagement.config;

import com.datum.clientmanagement.messaging.OutboxRelay;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;

/**
 * Runs {@link OutboxRelay} every {@code app.outbox.relay-interval} (default 5 s, NFR-7). Fixed delay, so a slow run
 * never overlaps the next one on the same instance; the first run waits one interval so startup stays quiet.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class OutboxRelayScheduling implements SchedulingConfigurer {

	private final OutboxRelay relay;
	private final Duration interval;

	OutboxRelayScheduling(OutboxRelay relay, OutboxProperties properties) {
		this.relay = relay;
		this.interval = properties.relayInterval();
	}

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		registrar.addFixedDelayTask(new FixedDelayTask(relay::relayPending, interval, interval));
	}
}
