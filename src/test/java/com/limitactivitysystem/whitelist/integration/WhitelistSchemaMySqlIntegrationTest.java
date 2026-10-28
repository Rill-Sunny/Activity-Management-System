package com.limitactivitysystem.whitelist.integration;

import com.limitactivitysystem.security.JwtTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Exercise the migration's constraints without changing permanent tables or Flyway history.
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class WhitelistSchemaMySqlIntegrationTest {

    @Autowired private DataSource dataSource;

    private Connection connection;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createTemporaryTablesFromMigration() throws Exception {
        connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));

        var migration = new ClassPathResource("db/migration/V3__create_whitelist_tables.sql");
        String ddl;
        try (var input = migration.getInputStream()) {
            ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String temporaryDdl = ddl.replace("CREATE TABLE ", "CREATE TEMPORARY TABLE ");
        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                new ByteArrayResource(temporaryDdl.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
    }

    @AfterEach
    void removeOnlyThisConnectionsTemporaryTables() throws Exception {
        if (connection == null) {
            return;
        }
        try (Connection fixtureConnection = connection) {
            fixtureConnection.rollback();
            try (Statement statement = fixtureConnection.createStatement()) {
                statement.execute("DROP TEMPORARY TABLE IF EXISTS whitelist_member");
                statement.execute("DROP TEMPORARY TABLE IF EXISTS whitelist_config");
            }
        }
    }

    @Test
    void missingConfigurationAndExplicitEmptyConfigurationAreDifferentFacts() {
        assertThat(configCount("ACTIVITY", 101)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM whitelist_member", Long.class)).isZero();

        long configId = insertConfig("ACTIVITY", 101);

        assertThat(configId).isPositive();
        assertThat(configCount("ACTIVITY", 101)).isEqualTo(1);
        assertThat(memberCount(configId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM whitelist_config
                WHERE config_id = ? AND created_at IS NOT NULL AND updated_at >= created_at
                """, Long.class, configId)).isEqualTo(1);
    }

    @Test
    void removingTheLastMemberKeepsAnExplicitEmptyConfigurationUntilConfigRemoval() {
        long configId = insertConfig("SESSION", 201);
        jdbc.update("INSERT INTO whitelist_member (config_id, user_id) VALUES (?, ?)", configId, 301);

        assertThat(jdbc.update("DELETE FROM whitelist_member WHERE config_id = ?", configId)).isEqualTo(1);
        assertThat(configCount("SESSION", 201)).isEqualTo(1);
        assertThat(memberCount(configId)).isZero();

        assertThat(jdbc.update("DELETE FROM whitelist_config WHERE config_id = ?", configId)).isEqualTo(1);
        assertThat(configCount("SESSION", 201)).isZero();
    }

    @Test
    void eachScopeHasOneConfigAndActivityAndSessionIdsDoNotCollide() {
        long activityConfigId = insertConfig("ACTIVITY", 101);

        assertThatThrownBy(() -> insertConfig("ACTIVITY", 101))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_whitelist_config_scope");

        long sessionConfigId = insertConfig("SESSION", 101);
        assertThat(sessionConfigId).isNotEqualTo(activityConfigId);
        assertThat(configCount("ACTIVITY", 101)).isEqualTo(1);
        assertThat(configCount("SESSION", 101)).isEqualTo(1);
    }

    @Test
    void membershipIsUniqueWithinAConfigAndTheSameUserCanJoinOtherConfigs() {
        long activityConfigId = insertConfig("ACTIVITY", 101);
        long sessionConfigId = insertConfig("SESSION", 201);
        jdbc.update("INSERT INTO whitelist_member (config_id, user_id) VALUES (?, ?)", activityConfigId, 301);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO whitelist_member (config_id, user_id) VALUES (?, ?)", activityConfigId, 301))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_whitelist_member_config_user");

        jdbc.update("INSERT INTO whitelist_member (config_id, user_id) VALUES (?, ?)", activityConfigId, 302);
        jdbc.update("INSERT INTO whitelist_member (config_id, user_id) VALUES (?, ?)", sessionConfigId, 301);
        assertThat(memberCount(activityConfigId)).isEqualTo(2);
        assertThat(memberCount(sessionConfigId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRODUCT", "UNKNOWN", ""})
    void unsupportedScopesAreRejectedByTheDatabase(String scopeType) {
        assertThatThrownBy(() -> insertConfig(scopeType, 101))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(3819))
                .hasMessageContaining("chk_whitelist_config_scope_type");
    }

    private long insertConfig(String scopeType, long scopeId) {
        jdbc.update("INSERT INTO whitelist_config (scope_type, scope_id) VALUES (?, ?)", scopeType, scopeId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long configCount(String scopeType, long scopeId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM whitelist_config WHERE scope_type = ? AND scope_id = ?
                """, Long.class, scopeType, scopeId);
    }

    private long memberCount(long configId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM whitelist_member WHERE config_id = ?", Long.class, configId);
    }
}
