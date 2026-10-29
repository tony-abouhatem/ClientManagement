package com.datum.clientmanagement.messaging;

import com.datum.clientmanagement.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * The relay's locking and transaction boundaries against real PostgreSQL: the lock query Hibernate generates, that
 * locked rows are skipped instead of waited for, and that each event is committed before the next one is sent.
 */
@SpringBootTest(properties = {"app.outbox.relay-interval=1h",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.datum.clientmanagement.messaging.RecordingStatementInspector"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
class OutboxRelayTransactionTest {

    private static final long WAIT_SECONDS = 10;
    private static final Pattern LOCK_QUERY = Pattern.compile("(?i)select .* from outbox_events .*for .*update.*");
    private static final String INSERT_OUTBOX_ROW = "INSERT INTO outbox_events"
            + " (event_id, event_type, aggregate_id, payload, created_at, next_attempt_at)"
            + " VALUES (?, 'CUSTOMER_CREATED', 1, CAST(? AS jsonb), ?, ?)";

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @MockitoSpyBean
    private ConfirmedPublisher publisher;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        amqpAdmin.purgeQueue(MessagingTopology.CUSTOMER_CREATED_QUEUE, false);
    }

    @Test
    void lockDue_generatesSelectForUpdateSkipLockedWithALimit() {
        insertPendingRow(Instant.now().minusSeconds(1));
        RecordingStatementInspector.clear();

        relay.relayPending();

        String lockQuery = RecordingStatementInspector.statements().stream()
                .filter(sql -> LOCK_QUERY.matcher(sql).matches())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no locking select on outbox_events was issued"));
        assertThat(lockQuery).containsIgnoringCase("skip locked");
        assertThat(lockQuery).containsPattern("(?i)(fetch first \\? rows only|limit \\?)");
    }

    @Test
    void lockDue_skipsRowsLockedByAnotherTransactionInsteadOfWaiting() throws Exception {
        Instant oldest = Instant.now().minusSeconds(10);
        UUID lockedElsewhere = insertPendingRow(oldest);
        UUID free = insertPendingRow(oldest.plusSeconds(1));
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> otherRelay = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbcTemplate.queryForList("SELECT id FROM outbox_events WHERE event_id = ? FOR UPDATE",
                            lockedElsewhere);
                    rowLocked.countDown();
                    awaitLatch(release);
                }));

        try {
            assertThat(rowLocked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            List<UUID> lockedHere = new TransactionTemplate(transactionManager).execute(status ->
                    repository.lockDue(Instant.now(), Integer.MAX_VALUE, Limit.of(10)).stream()
                            .map(OutboxEventEntity::getEventId)
                            .toList());

            assertThat(lockedHere).containsExactly(free);
        } finally {
            release.countDown();
            otherRelay.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    void relayPending_commitsEachEventBeforeSendingTheNext() {
        Instant oldest = Instant.now().minusSeconds(10);
        UUID first = insertPendingRow(oldest);
        insertPendingRow(oldest.plusSeconds(1));
        List<Timestamp> firstSentAtSeenFromOtherConnection = new ArrayList<>();
        doAnswer(invocation -> {
            if (!invocation.getArgument(0).equals(first)) {
                // Another thread uses another connection, so it only sees what is already committed.
                firstSentAtSeenFromOtherConnection.add(CompletableFuture.supplyAsync(() -> sentAtOf(first))
                        .get(WAIT_SECONDS, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(publisher).publish(any(UUID.class), anyString());

        relay.relayPending();

        assertThat(firstSentAtSeenFromOtherConnection).singleElement().isNotNull();
    }

    private UUID insertPendingRow(Instant createdAt) {
        UUID eventId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"CUSTOMER_CREATED\",\"customerId\":1,"
                + "\"createdAt\":\"" + createdAt + "\"}";
        jdbcTemplate.update(INSERT_OUTBOX_ROW, eventId, payload, Timestamp.from(createdAt), Timestamp.from(createdAt));
        return eventId;
    }

    private Timestamp sentAtOf(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT sent_at FROM outbox_events WHERE event_id = ?", Timestamp.class,
                eventId);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
