package com.limitactivitysystem.activity.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

final class PaymentInvariantAssertions {
    private PaymentInvariantAssertions() { }

    static void assertConsistent(JdbcTemplate jdbc) {
        assertThat(violations(jdbc)).as("tests/sql/payment_invariants.sql must return no violations").isEmpty();
    }

    static List<Map<String, Object>> violations(JdbcTemplate jdbc) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("Reconcile committed state after all worker transactions finish").isFalse();
        try {
            // This owned script uses line comments and semicolon-separated SELECTs, with no delimiters in literals.
            String sql = Files.readString(Path.of("tests", "sql", "payment_invariants.sql"), StandardCharsets.UTF_8)
                    .lines().filter(line -> !line.stripLeading().startsWith("--")).collect(Collectors.joining("\n"));
            List<Map<String, Object>> violations = new ArrayList<>();
            for (String statement : sql.split(";")) {
                if (!statement.isBlank()) { violations.addAll(jdbc.queryForList(statement)); }
            }
            return violations;
        } catch (IOException failure) { throw new IllegalStateException("Cannot read payment invariant SQL", failure); }
    }
}
