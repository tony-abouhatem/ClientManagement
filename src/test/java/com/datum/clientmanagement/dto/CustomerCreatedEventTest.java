package com.datum.clientmanagement.dto;

import com.datum.clientmanagement.model.EventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerCreatedEventTest {

	private static final long CUSTOMER_ID = 42L;
	private static final Instant CREATED_AT = Instant.parse("2026-10-28T10:15:30.123456Z");

	@Test
	void newEvent_isCustomerCreatedWithTheGivenCustomerAndCreatedAt() {
		CustomerCreatedEvent event = new CustomerCreatedEvent(CUSTOMER_ID, CREATED_AT);

		assertThat(event.eventType()).isEqualTo(EventType.CUSTOMER_CREATED);
		assertThat(event.customerId()).isEqualTo(CUSTOMER_ID);
		assertThat(event.createdAt()).isEqualTo(CREATED_AT);
	}

	@Test
	void newEvents_getTheirOwnEventId() {
		CustomerCreatedEvent first = new CustomerCreatedEvent(CUSTOMER_ID, CREATED_AT);
		CustomerCreatedEvent second = new CustomerCreatedEvent(CUSTOMER_ID, CREATED_AT);

		assertThat(first.eventId()).isNotNull();
		assertThat(second.eventId()).isNotNull().isNotEqualTo(first.eventId());
	}
}
