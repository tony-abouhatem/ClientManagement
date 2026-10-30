package com.datum.clientmanagement.service;

import com.datum.clientmanagement.dto.StatusChangeRequest;
import com.datum.clientmanagement.exception.InvalidStatusChangeException;
import com.datum.clientmanagement.model.enums.EventType;
import com.datum.clientmanagement.repository.ProcessedEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Applies a status change exactly once per eventId (ticket 07, NFR-8): the processed_events record and the status
 * update share one transaction. A redelivered eventId is skipped, so an old message can never undo a newer change
 * (AC-3). Any exception rolls both back. An unknown customer (the service's 404 {@link ResponseStatusException})
 * becomes an {@link InvalidStatusChangeException}, which goes straight to the DLQ; anything else is retried by the
 * listener container (NFR-9).
 */
@Component
public class StatusChangeProcessor {

    private final Logger log = LoggerFactory.getLogger(StatusChangeProcessor.class);

    private final ProcessedEventStore processedEvents;
    private final CustomerService customerService;

    StatusChangeProcessor(ProcessedEventStore processedEvents, CustomerService customerService) {
        this.processedEvents = processedEvents;
        this.customerService = customerService;
    }

    @Transactional
    public void process(StatusChangeRequest request) {
        if (!processedEvents.recordIfNew(request.eventId(), EventType.CUSTOMER_STATUS_CHANGE)) {
            log.info("Status change event {} was already processed; acknowledged as a duplicate", request.eventId());
            return;
        }
        try {
            customerService.changeStatus(request.customerId(), request.status());
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw exception;
            }
            // getReason(), not getMessage(): the latter is prefixed with "404 NOT_FOUND".
            throw new InvalidStatusChangeException("Event " + request.eventId() + ": " + exception.getReason());
        }
        log.info("Status change event {} processed for customer {}", request.eventId(), request.customerId());
    }
}
