package com.limitactivitysystem.activity.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

final class FreeReservationInvariantAssertions {
    private FreeReservationInvariantAssertions() { }
    static void assertConsistent(JdbcTemplate jdbc) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(violations(jdbc)).as("reservation_invariants.sql must return no violations").isEmpty();
    }
    static List<Map<String, Object>> violations(JdbcTemplate jdbc) {
        try {
            String sql = Files.readString(Path.of("tests", "sql", "reservation_invariants.sql")).lines()
                    .filter(line -> !line.stripLeading().startsWith("--")).collect(Collectors.joining("\n"));
            List<Map<String, Object>> found = new ArrayList<>();
            for (String statement : sql.split(";")) { if (!statement.isBlank()) { found.addAll(jdbc.queryForList(statement)); } }
            return found;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
