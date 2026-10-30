package com.datum.clientmanagement.repository;

import com.datum.clientmanagement.model.EventType;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcessedEventStoreTest {

	private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

	private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
	private final ProcessedEventStore store = new ProcessedEventStore(jdbcTemplate);

	@Test
	void recordIfNew_rowInserted_returnsTrue() {
		when(jdbcTemplate.update(contains("ON CONFLICT (event_id) DO NOTHING"), eq(EVENT_ID),
				eq("CUSTOMER_STATUS_CHANGE"))).thenReturn(1);

		assertThat(store.recordIfNew(EVENT_ID, EventType.CUSTOMER_STATUS_CHANGE)).isTrue();
	}

	@Test
	void recordIfNew_eventIdAlreadyPresent_returnsFalse() {
		when(jdbcTemplate.update(anyString(), eq(EVENT_ID), eq("CUSTOMER_STATUS_CHANGE"))).thenReturn(0);

		assertThat(store.recordIfNew(EVENT_ID, EventType.CUSTOMER_STATUS_CHANGE)).isFalse();
	}
}
