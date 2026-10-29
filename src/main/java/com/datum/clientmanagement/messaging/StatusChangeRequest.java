package com.datum.clientmanagement.messaging;

import com.datum.clientmanagement.customer.CustomerStatus;

import java.util.UUID;

/** A validated CUSTOMER_STATUS_CHANGE message (ticket 07): set {@code customerId} to {@code status}, once per eventId. */
public record StatusChangeRequest(UUID eventId, long customerId, CustomerStatus status) {
}
