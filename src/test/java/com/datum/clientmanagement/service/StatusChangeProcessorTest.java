package com.datum.clientmanagement.service;

import com.datum.clientmanagement.dto.StatusChangeRequest;
import com.datum.clientmanagement.exception.InvalidStatusChangeException;
import com.datum.clientmanagement.model.enums.CustomerStatus;
import com.datum.clientmanagement.model.enums.EventType;
import com.datum.clientmanagement.repository.ProcessedEventStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatusChangeProcessorTest {

    private static final UUID EVENT_ID = UUID.fromString("7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d");
    private static final long CUSTOMER_ID = 123L;
    private static final StatusChangeRequest REQUEST =
            new StatusChangeRequest(EVENT_ID, CUSTOMER_ID, CustomerStatus.INACTIVE);

    private final ProcessedEventStore processedEvents = mock(ProcessedEventStore.class);
    private final CustomerService customerService = mock(CustomerService.class);
    private final StatusChangeProcessor processor = new StatusChangeProcessor(processedEvents, customerService);

    @Test
    void process_newEvent_recordsItAndChangesTheStatus() {
        when(processedEvents.recordIfNew(EVENT_ID, EventType.CUSTOMER_STATUS_CHANGE)).thenReturn(true);

        processor.process(REQUEST);

        // changeStatus is only reached when the stubbed recordIfNew call returned true.
        verify(customerService).changeStatus(CUSTOMER_ID, CustomerStatus.INACTIVE);
    }

    @Test
    void process_alreadyProcessedEvent_doesNotTouchTheCustomer() {
        when(processedEvents.recordIfNew(EVENT_ID, EventType.CUSTOMER_STATUS_CHANGE)).thenReturn(false);

        processor.process(REQUEST);

        verify(customerService, never()).changeStatus(anyLong(), any());
    }

    @Test
    void process_unknownCustomer_isRejectedAsInvalidWithoutTheCpfOrRetry() {
        when(processedEvents.recordIfNew(EVENT_ID, EventType.CUSTOMER_STATUS_CHANGE)).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "No customer exists with id 123."))
                .when(customerService).changeStatus(anyLong(), any());

        assertThatThrownBy(() -> processor.process(REQUEST))
                .isInstanceOf(InvalidStatusChangeException.class)
                .hasMessage("Event " + EVENT_ID + ": No customer exists with id 123.");
    }

    @Test
    void process_otherResponseStatusFailure_propagatesUnchanged() {
        when(processedEvents.recordIfNew(EVENT_ID, EventType.CUSTOMER_STATUS_CHANGE)).thenReturn(true);
        ResponseStatusException conflict = new ResponseStatusException(HttpStatus.CONFLICT, "Version conflict.");
        doThrow(conflict).when(customerService).changeStatus(anyLong(), any());

        assertThatThrownBy(() -> processor.process(REQUEST)).isSameAs(conflict);
    }

    @Test
    void process_transientDatabaseFailure_propagatesForRetry() {
        DataAccessResourceFailureException outage = new DataAccessResourceFailureException("database down");
        when(processedEvents.recordIfNew(any(), any())).thenThrow(outage);

        assertThatThrownBy(() -> processor.process(REQUEST)).isSameAs(outage);
    }

    @Test
    void process_optimisticLockConflict_propagatesForRetry() {
        when(processedEvents.recordIfNew(EVENT_ID, EventType.CUSTOMER_STATUS_CHANGE)).thenReturn(true);
        ObjectOptimisticLockingFailureException conflict =
                new ObjectOptimisticLockingFailureException("Customer", CUSTOMER_ID);
        doThrow(conflict).when(customerService).changeStatus(anyLong(), any());

        assertThatThrownBy(() -> processor.process(REQUEST)).isSameAs(conflict);
    }
}
