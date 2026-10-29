package com.datum.clientmanagement.messaging;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records the SQL Hibernate sends, without touching log levels (those are global and would leak into other test
 * classes). Registered per context through {@code spring.jpa.properties.hibernate.session_factory.statement_inspector}.
 */
public class RecordingStatementInspector implements StatementInspector {

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    static List<String> statements() {
        return List.copyOf(STATEMENTS);
    }

    static void clear() {
        STATEMENTS.clear();
    }

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }
}
