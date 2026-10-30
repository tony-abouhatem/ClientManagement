package com.datum.clientmanagement.repository;

import com.datum.clientmanagement.model.entity.OutboxEventEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

    // A lock timeout of -2 makes Hibernate emit SKIP LOCKED: rows another relay (thread or app instance) holds are
    // skipped instead of waited for, so no row is sent twice concurrently (AC-7).
    String SKIP_LOCKED = "-2";

    /**
     * Locks the oldest due pending events. Dead rows ({@code attempts >= maxAttempts}) are left alone. Only
     * meaningful inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    @Query("""
            SELECT event FROM OutboxEventEntity event
            WHERE event.sentAt IS NULL
              AND event.attempts < :maxAttempts
              AND (event.nextAttemptAt IS NULL OR event.nextAttemptAt <= :now)
            ORDER BY event.createdAt, event.id""")
    List<OutboxEventEntity> lockDue(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts, Limit limit);
}
