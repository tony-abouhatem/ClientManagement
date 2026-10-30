package com.datum.clientmanagement.messaging;

import com.datum.clientmanagement.config.OutboxProperties;
import com.datum.clientmanagement.exception.PublishFailedException;
import com.datum.clientmanagement.model.entity.OutboxEventEntity;
import com.datum.clientmanagement.repository.OutboxEventRepository;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Resends pending outbox events with their original {@code eventId} (ADR-5, ticket 06 AC-5..AC-7). Each event gets
 * its own short transaction: lock the oldest due row (skipping rows another relay holds), publish, record the
 * outcome, commit. So a transaction lasts at most about one confirm timeout, and overlapping relays never send the
 * same row. A failed event is pushed to a later {@code next_attempt_at}, so the same run does not pick it again.
 * <p>
 * Resilience4j retry {@code outbox} supplies the wait after the n-th failure and the max-attempts; nothing sleeps
 * in-thread. Every failure type counts the same. The failure that brings {@code attempts} to max-attempts is logged
 * as an ERROR and the row becomes dead: the lock query skips it until someone resets {@code attempts} (or raises
 * max-attempts).
 */
@Component
public class OutboxRelay {

	static final String RETRY_INSTANCE = "outbox";

	private final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
	private final Limit ONE_ROW = Limit.of(1);

	private final OutboxEventRepository outbox;
	private final ConfirmedPublisher publisher;
	private final Clock clock;
	private final TransactionTemplate transaction;
	private final IntervalBiFunction<Object> retryDelays;
	private final int maxAttempts;
	private final int batchSize;

	OutboxRelay(OutboxEventRepository outbox, ConfirmedPublisher publisher, Clock clock,
			PlatformTransactionManager transactionManager, OutboxProperties properties, RetryRegistry retryRegistry) {
		this.outbox = outbox;
		this.publisher = publisher;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
		RetryConfig retryConfig = retryRegistry.retry(RETRY_INSTANCE).getRetryConfig();
		this.retryDelays = retryConfig.getIntervalBiFunction();
		this.maxAttempts = retryConfig.getMaxAttempts();
		this.batchSize = properties.batchSize();
	}

	/** Relays up to {@code batch-size} due events, one transaction each. Called on a schedule. */
	public void relayPending() {
		int relayed = 0;
		while (relayed < batchSize && Boolean.TRUE.equals(transaction.execute(status -> relayOldestDue())))
			relayed++;
	}

	/** Returns false when no due event is left. */
	private boolean relayOldestDue() {
		return outbox.lockDue(clock.instant(), maxAttempts, ONE_ROW).stream()
				.findFirst()
				.map(event -> {
					relay(event);
					return true;
				})
				.orElse(false);
	}

	private void relay(OutboxEventEntity event) {
		try {
			publisher.publish(event.getEventId(), event.getPayload());
			event.markSent(clock.instant());
			log.info("Relayed outbox event {} after {} failed attempts", event.getEventId(), event.getAttempts());
		} catch (PublishFailedException exception) {
			recordFailure(event, exception);
		}
	}

	private void recordFailure(OutboxEventEntity event, PublishFailedException failure) {
		Instant failedAt = clock.instant();
		Instant retryAt = failedAt.plus(delayAfterFailure(event.getAttempts() + 1, failure));
		event.markFailed(failure, failedAt, retryAt);
		if (event.getAttempts() >= maxAttempts) {
			log.error("Outbox event {} gave up after {} attempts (last error {}); it stays in outbox_events as a dead"
					+ " row until it is replayed manually", event.getEventId(), event.getAttempts(),
					failure.getReason());
		} else {
			log.warn("Outbox event {} failed attempt {} ({}), next attempt at {}: {}", event.getEventId(),
					event.getAttempts(), failure.getReason(), retryAt, failure.getMessage());
		}
	}

	private Duration delayAfterFailure(int failedAttempts, PublishFailedException failure) {
		return Duration.ofMillis(retryDelays.apply(failedAttempts, Either.left(failure)));
	}
}
