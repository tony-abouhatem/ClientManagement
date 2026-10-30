package com.datum.clientmanagement.dto;

import com.datum.clientmanagement.model.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Raised inside the creation transaction. Listeners that talk to the outside world must only react after the
 * commit, so they never announce a customer that was rolled back (ticket 06). It is also the exact CUSTOMER_CREATED
 * message body: the component order is the AC-1 body order. Consumers dedupe by eventId.
 */
public record CustomerCreatedEvent(
	UUID eventId,
	EventType eventType,
	long customerId,
	Instant createdAt
) {
	public CustomerCreatedEvent(long customerId, Instant createdAt) {
		this(UUID.randomUUID(), EventType.CUSTOMER_CREATED, customerId, createdAt);
	}
}
