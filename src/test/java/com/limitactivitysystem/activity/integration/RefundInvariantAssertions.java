package com.limitactivitysystem.activity.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

final class RefundInvariantAssertions {
    private RefundInvariantAssertions() { }

    static void assertConsistent(JdbcTemplate jdbc) {
        assertThat(violations(jdbc)).as("tests/sql/refund_invariants.sql must return no violations").isEmpty();
    }

    static List<Map<String, Object>> violations(JdbcTemplate jdbc) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("Reconcile committed state after all worker transactions finish").isFalse();
        try {
            String sql = Files.readString(Path.of("tests", "sql", "refund_invariants.sql"), StandardCharsets.UTF_8)
                    .lines().filter(line -> !line.stripLeading().startsWith("--")).collect(Collectors.joining("\n"));
            var snapshot = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
            snapshot.setReadOnly(true);
            snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return snapshot.execute(status -> {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (String statement : sql.split(";")) {
                    if (!statement.isBlank()) { rows.addAll(jdbc.queryForList(statement)); }
                }
                return rows;
            });
        } catch (IOException failure) { throw new IllegalStateException("Cannot read refund invariant SQL", failure); }
    }
}
