package com.datum.clientmanagement.model.enums;

/** The eventType values on the wire, also stored by name as the event_type of outbox and processed_events rows. */
public enum EventType {
    CUSTOMER_CREATED,
    CUSTOMER_STATUS_CHANGE
}
