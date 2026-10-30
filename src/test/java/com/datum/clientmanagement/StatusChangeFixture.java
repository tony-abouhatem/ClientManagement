package com.datum.clientmanagement;

import com.datum.clientmanagement.config.MessagingTopology;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Publishes CUSTOMER_STATUS_CHANGE messages (ticket 07) and reads the state they change. */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
public final class StatusChangeFixture {

    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;

    public StatusChangeFixture(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }

    /** The wire payload, written out by hand so the tests pin the format. */
    public static String statusChange(UUID eventId, long customerId, String status) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":" + customerId
                + ",\"status\":\"" + status + "\"}";
    }

    public void publish(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(MessagingTopology.CUSTOMER_EVENTS_EXCHANGE, MessagingTopology.STATUS_CHANGE_ROUTING_KEY,
                new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    /**
     * Messages in the DLQ, through a passive declare on the AMQP connection (no {@code rabbitmqctl} exec). It counts
     * ready messages only, which is all of them because nothing consumes the DLQ.
     */
    public long deadLetterCount() {
        return rabbitTemplate.execute(channel -> channel.messageCount(MessagingTopology.STATUS_CHANGE_DEAD_LETTER_QUEUE));
    }

    public String statusOf(long customerId) {
        return jdbcTemplate.queryForObject("SELECT status FROM customers WHERE id = ?", String.class, customerId);
    }

    public long versionOf(long customerId) {
        return jdbcTemplate.queryForObject("SELECT version FROM customers WHERE id = ?", Long.class, customerId);
    }

    public long processedRows(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Long.class,
                eventId);
    }
}
