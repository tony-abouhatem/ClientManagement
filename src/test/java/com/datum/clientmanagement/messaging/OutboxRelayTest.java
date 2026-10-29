package com.datum.clientmanagement.messaging;

import com.datum.clientmanagement.config.OutboxProperties;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The repository and transaction manager are mocked; locking itself is covered by OutboxRelayTransactionTest. */
@ExtendWith(OutputCaptureExtension.class)
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static final int BATCH_SIZE = 3;
    private static final int MAX_ATTEMPTS = 4;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Limit ONE_ROW = Limit.of(1);

    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final ConfirmedPublisher publisher = mock(ConfirmedPublisher.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    // Same wait settings as resilience4j.retry.instances.outbox in application.yaml, with a small max-attempts.
    private final RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
            .maxAttempts(MAX_ATTEMPTS)
            .intervalFunction(IntervalFunction.ofExponentialBackoff(INITIAL_BACKOFF, 2, Duration.ofSeconds(15)))
            .build());
    private final OutboxRelay relay = new OutboxRelay(outbox, publisher, Clock.fixed(NOW, ZoneOffset.UTC),
            transactionManager, new OutboxProperties(Duration.ofSeconds(5), BATCH_SIZE), retryRegistry);

    @BeforeEach
    void openTransactions() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Test
    void relayPending_sendsEachDueEventInItsOwnTransactionAndMarksItSent() {
        OutboxEventEntity first = pendingEvent();
        OutboxEventEntity second = pendingEvent();
        when(outbox.lockDue(NOW, MAX_ATTEMPTS, ONE_ROW)).thenReturn(List.of(first), List.of(second), List.of());

        relay.relayPending();

        InOrder order = inOrder(publisher, transactionManager);
        order.verify(transactionManager).getTransaction(any());
        order.verify(publisher).publish(first.getEventId(), first.getPayload());
        order.verify(transactionManager).commit(any());
        order.verify(transactionManager).getTransaction(any());
        order.verify(publisher).publish(second.getEventId(), second.getPayload());
        order.verify(transactionManager).commit(any());
        assertThat(first.getSentAt()).isEqualTo(NOW);
        assertThat(second.getSentAt()).isEqualTo(NOW);
    }

    @Test
    void relayPending_stopsAtTheBatchSize() {
        when(outbox.lockDue(NOW, MAX_ATTEMPTS, ONE_ROW)).thenAnswer(invocation -> List.of(pendingEvent()));

        relay.relayPending();

        verify(outbox, times(BATCH_SIZE)).lockDue(NOW, MAX_ATTEMPTS, ONE_ROW);
        verify(publisher, times(BATCH_SIZE)).publish(any(), anyString());
    }

    @Test
    void relayPending_failedPublish_recordsAttemptErrorTypeTimeAndBackoffAndKeepsGoing() {
        OutboxEventEntity failing = pendingEvent();
        OutboxEventEntity next = pendingEvent();
        when(outbox.lockDue(NOW, MAX_ATTEMPTS, ONE_ROW)).thenReturn(List.of(failing), List.of(next), List.of());
        doThrow(new PublishFailedException(PublishFailureReason.BROKER_DOWN, "Connection refused")).when(publisher)
                .publish(failing.getEventId(), failing.getPayload());

        relay.relayPending();

        assertThat(failing.getAttempts()).isEqualTo(1);
        assertThat(failing.getLastError()).isEqualTo("Connection refused");
        assertThat(failing.getLastErrorType()).isEqualTo(PublishFailureReason.BROKER_DOWN);
        assertThat(failing.getLastFailedAt()).isEqualTo(NOW);
        assertThat(failing.getNextAttemptAt()).isEqualTo(NOW.plus(INITIAL_BACKOFF));
        assertThat(failing.getSentAt()).isNull();
        assertThat(next.getSentAt()).isEqualTo(NOW);
    }

    @Test
    void relayPending_repeatedFailure_growsTheBackoff() {
        OutboxEventEntity event = eventWithFailedAttempts(2);
        when(outbox.lockDue(NOW, MAX_ATTEMPTS, ONE_ROW)).thenReturn(List.of(event), List.of());
        doThrow(new PublishFailedException(PublishFailureReason.NACK, "nack")).when(publisher)
                .publish(any(), anyString());

        relay.relayPending();

        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getLastErrorType()).isEqualTo(PublishFailureReason.NACK);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(4)));
    }

    @Test
    void relayPending_failureReachingMaxAttempts_logsOneErrorWithEventIdAttemptsAndType(CapturedOutput output) {
        OutboxEventEntity event = eventWithFailedAttempts(MAX_ATTEMPTS - 1);
        when(outbox.lockDue(NOW, MAX_ATTEMPTS, ONE_ROW)).thenReturn(List.of(event), List.of());
        doThrow(new PublishFailedException(PublishFailureReason.UNROUTABLE, "NO_ROUTE")).when(publisher)
                .publish(any(), anyString());

        relay.relayPending();

        assertThat(event.getAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(output.getOut().lines().filter(line -> line.contains("ERROR")).toList())
                .singleElement()
                .satisfies(line -> assertThat(line).contains(event.getEventId().toString(),
                        MAX_ATTEMPTS + " attempts", "UNROUTABLE"));
    }

    @Test
    void relayPending_failureBelowMaxAttempts_logsNoError(CapturedOutput output) {
        OutboxEventEntity event = eventWithFailedAttempts(MAX_ATTEMPTS - 2);
        when(outbox.lockDue(NOW, MAX_ATTEMPTS, ONE_ROW)).thenReturn(List.of(event), List.of());
        doThrow(new PublishFailedException(PublishFailureReason.CONFIRM_TIMEOUT, "timeout")).when(publisher)
                .publish(any(), anyString());

        relay.relayPending();

        assertThat(output.getOut()).doesNotContain("ERROR");
    }

    @Test
    void relayPending_nothingDue_publishesNothing() {
        when(outbox.lockDue(NOW, MAX_ATTEMPTS, ONE_ROW)).thenReturn(List.of());

        relay.relayPending();

        verify(outbox).lockDue(NOW, MAX_ATTEMPTS, ONE_ROW);
        verify(publisher, never()).publish(any(), anyString());
    }

    private static OutboxEventEntity pendingEvent() {
        UUID eventId = UUID.randomUUID();
        return OutboxEventEntity.afterFailedPublish(eventId, EventType.CUSTOMER_CREATED, 1L,
                "{\"eventId\":\"" + eventId + "\"}",
                new PublishFailedException(PublishFailureReason.BROKER_DOWN, "first publish"), NOW.minusSeconds(1));
    }

    private static OutboxEventEntity eventWithFailedAttempts(int failedAttempts) {
        OutboxEventEntity event = pendingEvent();
        for (int attempt = 0; attempt < failedAttempts; attempt++) {
            event.markFailed(new PublishFailedException(PublishFailureReason.BROKER_DOWN, "earlier"), NOW, NOW);
        }
        return event;
    }
}
