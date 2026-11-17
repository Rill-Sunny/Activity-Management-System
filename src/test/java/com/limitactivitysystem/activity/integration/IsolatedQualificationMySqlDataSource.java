package com.limitactivitysystem.activity.integration;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one random schema. V2 is deliberately excluded because it contains USE limit_activity_system. */
final class IsolatedQualificationMySqlDataSource extends HikariDataSource {
    private final String schema = "qual_it_" + UUID.randomUUID().toString().replace("-", "");
    private final String baseUrl;
    private final String login;
    private final String credential;
    private final AtomicBoolean disposed = new AtomicBoolean();
    private boolean schemaCreated;

    IsolatedQualificationMySqlDataSource(DataSourceProperties properties) throws Exception {
        this(properties, 6);
    }

    IsolatedQualificationMySqlDataSource(DataSourceProperties properties, int maximumPoolSize) throws Exception {
        this(properties, maximumPoolSize, List.of(
                "V1__create_schema.sql", "V3__create_whitelist_tables.sql", "V4__create_free_reservation_tables.sql",
                "V5__create_ticket_refund_tables.sql", "V6__add_refund_processing_results.sql", "V7__add_gift_inventory_operations.sql",
                "V8__create_product_catalog.sql", "V9__create_product_orders.sql", "V10__add_qualification_guard_and_order_version.sql",
                "V11__freeze_product_after_sales.sql", "V12__separate_after_sale_inspection_and_resalability.sql",
                "V13__create_cache_invalidation_tasks.sql", "V14__add_transactional_outbox_and_notification_processing.sql",
                "V15__add_admin_role_for_outbox_replay.sql", "V16__add_ticket_qualification_after_sale_tasks.sql",
                "V17__add_session_reschedule_history.sql", "V18__add_cancellation_batches.sql")
                .stream().map(name -> "db/migration/" + name).toList());
    }

    /** Empty bootstrap lets migration tests create the schema through Flyway itself. */
    IsolatedQualificationMySqlDataSource(DataSourceProperties properties, int maximumPoolSize, List<String> bootstrapMigrations) throws Exception {
        baseUrl = properties.determineUrl();
        login = properties.determineUsername();
        credential = properties.determinePassword();
        int query = baseUrl.indexOf('?');
        String databaseUrl = query < 0 ? baseUrl : baseUrl.substring(0, query);
        int slash = databaseUrl.lastIndexOf('/');
        if (!baseUrl.startsWith("jdbc:mysql://") || slash < "jdbc:mysql://".length()) {
            throw new IllegalArgumentException("A MySQL URL with a database is required");
        }
        try {
            try (var admin = DriverManager.getConnection(baseUrl, login, credential);
                 var statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
                schemaCreated = true;
            }
            setJdbcUrl(databaseUrl.substring(0, slash + 1) + schema + (query < 0 ? "" : baseUrl.substring(query)));
            setUsername(login);
            setPassword(credential);
            setMaximumPoolSize(maximumPoolSize);
            if (maximumPoolSize > 6) { setMinimumIdle(4); }
            try (var connection = getConnection()) {
                for (String migration : bootstrapMigrations) {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource(migration));
                }
            }
        } catch (Exception exception) {
            try { close(); } catch (RuntimeException cleanup) { exception.addSuppressed(cleanup); }
            throw exception;
        }
    }

    @Override
    public void close() {
        if (!disposed.compareAndSet(false, true)) { return; }
        super.close();
        if (schemaCreated) {
            if (!schema.matches("qual_it_[0-9a-f]{32}")) {
                throw new IllegalStateException("Unexpected test schema name");
            }
            try (var admin = DriverManager.getConnection(baseUrl, login, credential);
                 var statement = admin.createStatement()) {
                statement.execute("DROP DATABASE `" + schema + "`");
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not remove the owned qualification test schema", exception);
            }
        }
    }
}
