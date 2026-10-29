package com.datum.clientmanagement.messaging;

import com.datum.clientmanagement.customer.CustomerCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Hybrid CUSTOMER_CREATED publishing (ADR-5): publish right after the creation commits and wait for the confirm;
 * if that fails for any reason, store the event in the outbox for {@link OutboxRelay} to resend. Either way the
 * customer creation itself has already succeeded and the client gets its 201.
 */
@Component
class CustomerCreatedPublisher {

    private static final Logger log = LoggerFactory.getLogger(CustomerCreatedPublisher.class);

    private final ConfirmedPublisher publisher;
    private final OutboxEventRepository outbox;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final TransactionTemplate newTransaction;

    CustomerCreatedPublisher(ConfirmedPublisher publisher, OutboxEventRepository outbox, JsonMapper jsonMapper,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.publisher = publisher;
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        // After commit the creation transaction is finished but still bound to the thread: writes need their own.
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onCustomerCreated(CustomerCreatedEvent event) {
        UUID eventId = UUID.randomUUID();
        String payload = jsonMapper.writeValueAsString(payloadOf(eventId, event));
        try {
            publisher.publish(eventId, payload);
            log.info("Published CUSTOMER_CREATED event {} for customer {}", eventId, event.customerId());
        } catch (PublishFailedException exception) {
            log.warn("CUSTOMER_CREATED event {} for customer {} not published, storing it in the outbox: {}",
                    eventId, event.customerId(), exception.getMessage());
            storeInOutbox(eventId, event.customerId(), payload, exception);
        }
    }

    private void storeInOutbox(UUID eventId, long customerId, String payload, PublishFailedException failure) {
        try {
            newTransaction.executeWithoutResult(status -> outbox.save(OutboxEventEntity.afterFailedPublish(eventId,
                    EventType.CUSTOMER_CREATED, customerId, payload, failure, clock.instant())));
        } catch (DataAccessException | TransactionException exception) {
            // The customer is already committed; failing the request now would only make the client retry into a
            // 409. Losing the event here is the gap ADR-5 accepts, so it is reported loudly instead.
            log.error("CUSTOMER_CREATED event {} for customer {} could not be stored in the outbox and is lost",
                    eventId, customerId, exception);
        }
    }

    /**
     * The message body (AC-1), in this field order. Values stay typed so the JsonMapper writes eventId as a UUID
     * string and createdAt as an ISO-8601 UTC string. Consumers dedupe by eventId.
     */
    private static Map<String, Object> payloadOf(UUID eventId, CustomerCreatedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventType", EventType.CUSTOMER_CREATED);
        payload.put("customerId", event.customerId());
        payload.put("createdAt", event.createdAt());
        return payload;
    }
}
