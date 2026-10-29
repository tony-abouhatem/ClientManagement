package com.datum.clientmanagement.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * An event that could not be published right away (ADR-5). It stays pending ({@code sentAt} null) until
 * {@link OutboxRelay} gets a confirm for it; {@code nextAttemptAt} holds it back after a failed attempt. Once
 * {@code attempts} reaches the relay's max-attempts it is a dead row, left for a manual replay.
 */
@Entity
@Table(name = "outbox_events")
class OutboxEventEntity {

    private static final int EVENT_TYPE_MAX_LENGTH = 50;
    private static final int ERROR_TYPE_MAX_LENGTH = 20;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = EVENT_TYPE_MAX_LENGTH)
    private EventType eventType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private long aggregateId;

    // The exact JSON that was (or will be) sent, so the relay republishes the original eventId.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Relay failures only; the first publish attempt, made right after commit, is not counted.
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "last_error")
    private String lastError;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_error_type", length = ERROR_TYPE_MAX_LENGTH)
    private PublishFailureReason lastErrorType;

    @Column(name = "last_failed_at")
    private Instant lastFailedAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxEventEntity() {
        // for JPA
    }

    private OutboxEventEntity(UUID eventId, EventType eventType, long aggregateId, String payload, Instant now) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    /**
     * An event whose publish right after commit failed: it keeps that failure's details, with {@code attempts}
     * still 0, and is due for the relay straight away.
     */
    static OutboxEventEntity afterFailedPublish(UUID eventId, EventType eventType, long aggregateId, String payload,
            PublishFailedException failure, Instant now) {
        OutboxEventEntity event = new OutboxEventEntity(eventId, eventType, aggregateId, payload, now);
        event.recordFailure(failure, now);
        return event;
    }

    void markSent(Instant now) {
        this.sentAt = now;
    }

    /** Records one more failed relay attempt and keeps the event pending until {@code retryAt}. */
    void markFailed(PublishFailedException failure, Instant failedAt, Instant retryAt) {
        this.attempts++;
        recordFailure(failure, failedAt);
        this.nextAttemptAt = retryAt;
    }

    private void recordFailure(PublishFailedException failure, Instant failedAt) {
        this.lastError = failure.getMessage();
        this.lastErrorType = failure.getReason();
        this.lastFailedAt = failedAt;
    }

    UUID getEventId() {
        return eventId;
    }

    String getPayload() {
        return payload;
    }

    int getAttempts() {
        return attempts;
    }

    String getLastError() {
        return lastError;
    }

    PublishFailureReason getLastErrorType() {
        return lastErrorType;
    }

    Instant getLastFailedAt() {
        return lastFailedAt;
    }

    Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    Instant getSentAt() {
        return sentAt;
    }
}
