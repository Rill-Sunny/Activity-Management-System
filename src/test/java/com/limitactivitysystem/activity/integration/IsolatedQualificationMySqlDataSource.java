package com.limitactivitysystem.activity.integration;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
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
            setMaximumPoolSize(6);
            try (var connection = getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V1__create_schema.sql"));
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V3__create_whitelist_tables.sql"));
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
