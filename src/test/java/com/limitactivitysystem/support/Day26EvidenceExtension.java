package com.limitactivitysystem.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Captures committed AT fixtures before @AfterEach removes them. Enabled only by the Day 26 runner. */
public final class Day26EvidenceExtension implements AfterTestExecutionCallback {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Set<String> OMIT = Set.of("password", "password_hash", "username", "payload", "content",
            "reason", "failure_reason", "last_error", "secret", "token");
    private static final List<String> TABLES = List.of("app_user", "activity", "activity_session", "session_zone",
            "session_seat", "free_session_quota", "free_reservation", "whitelist_config", "whitelist_member",
            "ticket_order", "ticket_order_item", "ticket", "user_session_purchase", "ticket_refund_request",
            "ticket_refund_item", "gift_inventory_operation", "gift_inventory_operation_item", "product", "sku",
            "product_order", "product_order_item", "user_product_purchase", "product_after_sale_request",
            "product_after_sale_item", "outbox_event", "consumer_process", "consumer_failure", "notification",
            "ticket_qualification_after_sale_task", "session_reschedule_event", "reschedule_affected_ticket",
            "reschedule_response", "reschedule_refund_link", "reschedule_refund_submission", "cancellation_event", "cancellation_scope", "cancellation_batch",
            "cancellation_batch_item", "flyway_schema_history");

    @Override
    public void afterTestExecution(ExtensionContext context) throws Exception {
        if (!"true".equals(System.getenv("RUN_DAY26_TESTS"))) { return; }
        String className = context.getRequiredTestClass().getName();
        String method = context.getRequiredTestMethod().getName();
        var ids = new ArrayList<String>();
        var requirements = new ArrayList<JsonNode>();
        JSON.readTree(Path.of("tests/fixtures/day26/acceptance-matrix.json").toFile()).forEach(requirements::add);
        if ("true".equals(System.getenv("RUN_DAY28_TESTS"))) {
            JSON.readTree(Path.of("tests/fixtures/day28/fault-matrix.json").toFile()).forEach(requirements::add);
        }
        for (JsonNode requirement : requirements) {
            for (JsonNode test : requirement.path("tests")) {
                if (className.equals(test.path("class").asText()) && method.equals(test.path("method").asText())) {
                    ids.add(requirement.path("id").asText()); break;
                }
            }
        }
        if (ids.isEmpty()) { return; }
        String destination = System.getenv("DAY26_EVIDENCE_DIRECTORY");
        if (destination == null || destination.isBlank()) { throw new IllegalStateException("Day 26 evidence path missing"); }
        Path directory = Path.of(destination).toAbsolutePath().normalize();
        boolean allowed = List.of("day26", "day27", "day28", "day29", "day30").stream()
                .anyMatch(day -> directory.startsWith(Path.of("docs/verification", day).toAbsolutePath().normalize()));
        if (!allowed) { throw new IllegalStateException("Evidence must remain in an owned Day 26-30 directory"); }
        Files.createDirectories(directory);
        String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(context.getUniqueId().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        Path target = directory.resolve(context.getRequiredTestClass().getSimpleName() + "-" + method + "-" + key + ".json");
        var report = new LinkedHashMap<String, Object>();
        report.put("requirements", ids); report.put("class", className); report.put("method", method);
        report.put("displayName", context.getDisplayName()); report.put("uniqueId", context.getUniqueId());
        report.put("observedAt", OffsetDateTime.now());
        report.put("testBodyException", context.getExecutionException().map(e -> e.getClass().getSimpleName()).orElse(null));
        report.put("phase", "AFTER_TEST_BEFORE_FIXTURE_CLEANUP");
        report.put("sourceCommit", System.getenv("DAY26_SOURCE_COMMIT"));
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("AT evidence cannot come from a surrounding rollback-only test transaction");
            }
            var application = SpringExtension.getApplicationContext(context);
            var mockMvc = AnnotatedElementUtils.findMergedAnnotation(context.getRequiredTestClass(), AutoConfigureMockMvc.class);
            if (mockMvc != null && !mockMvc.addFilters()) { throw new IllegalStateException("Security filters are disabled"); }
            report.put("securityFilterChains", application.getBeansOfType(SecurityFilterChain.class).size());
            report.put("surroundingTestTransaction", false);
            var source = application.getBean(JdbcTemplate.class).getDataSource();
            try (Connection connection = source.getConnection()) {
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setReadOnly(true); connection.setAutoCommit(false);
                try (var begin = connection.createStatement()) { begin.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY"); }
                try {
                    var identity = rows(connection, "SELECT DATABASE() AS schema_name, VERSION() AS mysql_version, CONNECTION_ID() AS connection_id, CURRENT_TIMESTAMP(3) AS database_time").getFirst();
                    String schema = identity.get("schema_name").toString();
                    if (!schema.matches("(qual_it_|day26_suite_)[a-f0-9]{32}")) { throw new IllegalStateException("Not an owned test schema"); }
                    if (!identity.get("mysql_version").toString().startsWith("8.4.11")) { throw new IllegalStateException("MySQL 8.4.11 is required"); }
                    report.put("database", identity);
                    var present = rows(connection, "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()").stream()
                            .map(row -> row.values().iterator().next().toString()).toList();
                    var records = new LinkedHashMap<String, Object>();
                    for (String table : TABLES) {
                        if (present.contains(table)) { records.put(table, rows(connection, "SELECT * FROM `" + table + "`")); }
                    }
                    report.put("records", records);
                    var audits = new ArrayList<Map<String, Object>>();
                    int differences = 0;
                    try (var paths = Files.list(Path.of("tests/sql"))) {
                        for (Path sql : paths.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                            var results = new ArrayList<Map<String, Object>>();
                            String statements = Files.readString(sql).replaceAll("(?m)--[^\\r\\n]*", "");
                            for (String statement : statements.split(";")) {
                                if (!statement.isBlank()) { results.addAll(rows(connection, statement)); }
                            }
                            boolean diagnostic = sql.getFileName().toString().equals("messaging_reconciliation.sql");
                            if (!diagnostic) { differences += results.size(); }
                            audits.add(Map.of("script", sql.toString().replace('\\', '/'), "diagnostic", diagnostic, "rows", results));
                        }
                    }
                    report.put("sql", audits); report.put("invariantDifferences", differences);
                    report.put("captureStatus", "CAPTURED");
                    JSON.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report);
                    if (context.getExecutionException().isEmpty() && differences != 0) {
                        throw new AssertionError("Day 26 committed AT fixture has " + differences + " invariant differences; see " + target.getFileName());
                    }
                } finally { connection.rollback(); }
            }
        } catch (Exception failure) {
            report.put("captureStatus", "ERROR"); report.put("captureErrorType", failure.getClass().getSimpleName());
            JSON.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report);
            throw failure;
        }
    }

    private static List<Map<String, Object>> rows(Connection connection, String sql) throws Exception {
        var values = new ArrayList<Map<String, Object>>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (ResultSet result = statement.executeQuery(sql)) {
                var columns = result.getMetaData();
                while (result.next()) {
                    if (values.size() >= 2000) { throw new IllegalStateException("Fixture exceeds evidence row bound"); }
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= columns.getColumnCount(); i++) {
                        String label = columns.getColumnLabel(i);
                        if (!OMIT.contains(label.toLowerCase(java.util.Locale.ROOT))) { row.put(label, result.getObject(i)); }
                    }
                    values.add(row);
                }
            }
        }
        return values;
    }
}
