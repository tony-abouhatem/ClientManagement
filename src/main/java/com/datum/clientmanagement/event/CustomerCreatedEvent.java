package com.datum.clientmanagement.event;

import java.time.Instant;

/**
 * Raised inside the creation transaction. Listeners that talk to the outside world must only react after the
 * commit, so they never announce a customer that was rolled back (ticket 06).
 */
public record CustomerCreatedEvent(
	long customerId,
	Instant createdAt
) {
}
