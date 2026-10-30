package com.datum.clientmanagement.repository;

import com.datum.clientmanagement.model.enums.EventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * The {@code processed_events} idempotency log (ADR-6). Must run inside the transaction that applies the event, so
 * the record and the change commit or roll back together.
 */
@Repository
public class ProcessedEventStore {

	// ON CONFLICT instead of catching the PK violation: a failed statement would abort the whole PostgreSQL
	// transaction. A concurrent duplicate blocks here until the first transaction ends, then inserts nothing.
	private static final String INSERT_IF_ABSENT = "INSERT INTO processed_events (event_id, event_type)"
			+ " VALUES (?, ?) ON CONFLICT (event_id) DO NOTHING";

	private final JdbcTemplate jdbcTemplate;

	ProcessedEventStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** Records the event; {@code false} means it was already recorded (a duplicate delivery). */
	public boolean recordIfNew(UUID eventId, EventType eventType) {
		return jdbcTemplate.update(INSERT_IF_ABSENT, eventId, eventType.name()) == 1;
	}
}
