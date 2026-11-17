package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.security.JwtTestTokens;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Storage contracts only. No GIFT allocation/release HTTP or Service is implemented by this test. */
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(GiftInventorySchemaMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class GiftInventorySchemaMySqlIntegrationTest extends PaymentResourceTestSupport {
    // Keep the historical schema target; cache tables were introduced in V13.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.limitactivitysystem.cache.CacheInvalidationRecorder cacheInvalidations;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.limitactivitysystem.cache.CacheInvalidationDelivery cacheDelivery;
    @Autowired private UpgradeObservation upgrade;
    private ActivitySession session;
    private SessionZone gift;

    @BeforeEach void createPublishedPaidSessionWithSeparateGiftSeats() throws Exception {
        var activity = insertDraft(publisher, "PAID"); session = insertSession(activity);
        gift = insertZone(session, "GIFT", 4, 4);
        insertZone(session, "PAID", 6, 6);
        publish(activity, publisher).andExpect(status().isNoContent());
    }

    @AfterEach void removeOnlyHistoryInTheOwnedIsolatedDatabase() {
        jdbc.update("DELETE FROM gift_inventory_operation_item");
        jdbc.update("DELETE FROM gift_inventory_operation");
    }

    @Test void flywayUpgradesV6ToV7PreservingAllExistingTablesAndRows() throws Exception {
        assertThat(upgrade.beforeVersion).isEqualTo("6"); assertThat(upgrade.afterVersion).isEqualTo("7");
        assertThat(upgrade.executed).isEqualTo(1); assertThat(upgrade.repeated).isZero();
        assertThat(upgrade.definitionsAfter).isEqualTo(upgrade.definitionsBefore);
        assertThat(upgrade.countsAfter).isEqualTo(upgrade.countsBefore);
        assertThat(upgrade.definitionsBefore).hasSize(15);
        assertThat(jdbc.queryForObject("SELECT created_at FROM app_user WHERE username = 'gift_v6_upgrade_fixture'", LocalDateTime.class))
                .isEqualTo(upgrade.retainedCreatedAt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'", Integer.class)).isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE() AND constraint_type = 'FOREIGN KEY'", Integer.class)).isZero();
        evidence("day12-gift-migration", Map.of("beforeVersion", upgrade.beforeVersion, "afterVersion", upgrade.afterVersion,
                "executed", upgrade.executed, "repeatExecuted", upgrade.repeated, "existingTablesUnchanged", 15,
                "businessTables", 17, "physicalForeignKeys", 0, "retainedFixtureTime", upgrade.retainedCreatedAt));
    }

    @Test void exactColumnsContainNoRecipientIdentityOrPaidTicketReferences() {
        assertThat(columns("gift_inventory_operation")).containsExactly("operation_id", "request_id", "publisher_id", "session_id",
                "zone_id", "operation_type", "quantity", "status", "created_at");
        assertThat(columns("gift_inventory_operation_item")).containsExactly("operation_item_id", "operation_id", "seat_id", "created_at");
        assertThat(jdbc.queryForList("SELECT engine FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('gift_inventory_operation', 'gift_inventory_operation_item')", String.class))
                .containsExactlyInAnyOrder("InnoDB", "InnoDB");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name IN ('gift_inventory_operation', 'gift_inventory_operation_item') AND is_nullable = 'YES'", String.class)).isEmpty();
    }

    @Test void primaryUniqueAndHistoryIndexesHaveTheFrozenColumnOrder() {
        index("gift_inventory_operation", "PRIMARY", List.of("operation_id"), false);
        index("gift_inventory_operation", "uq_gift_operation_request", List.of("publisher_id", "request_id"), false);
        index("gift_inventory_operation", "idx_gift_operation_zone_history", List.of("zone_id", "operation_id"), true);
        index("gift_inventory_operation", "idx_gift_operation_session_history", List.of("session_id", "operation_id"), true);
        index("gift_inventory_operation", "idx_gift_operation_publisher_created", List.of("publisher_id", "created_at", "operation_id"), true);
        index("gift_inventory_operation_item", "PRIMARY", List.of("operation_item_id"), false);
        index("gift_inventory_operation_item", "uq_gift_operation_item_seat", List.of("operation_id", "seat_id"), false);
        index("gift_inventory_operation_item", "idx_gift_operation_item_seat_history", List.of("seat_id", "operation_id"), true);
    }

    @Test void databaseGeneratesOperationItemIdsAndMillisecondCreationTimes() throws Exception {
        var before = now(); var resourcesBefore = resourcesSnapshot();
        Long operationId = operation(publisher.getId(), "history", 2);
        var selected = seatMapper.findByZoneId(gift.getZoneId()).subList(0, 2);
        selected.forEach(seat -> item(operationId, seat.getSeatId()));
        var saved = jdbc.queryForMap("SELECT * FROM gift_inventory_operation WHERE operation_id = ?", operationId);
        assertThat(operationId).isPositive(); assertThat(saved.get("operation_type")).isEqualTo("ALLOCATE");
        assertThat(saved.get("status")).isEqualTo("SUCCEEDED"); assertThat(saved.get("quantity")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT seat_id FROM gift_inventory_operation_item WHERE operation_id = ? ORDER BY seat_id", Long.class, operationId))
                .containsExactlyElementsOf(selected.stream().map(seat -> seat.getSeatId()).sorted().toList());
        var after = now();
        for (String table : List.of("gift_inventory_operation", "gift_inventory_operation_item")) {
            assertThat(jdbc.queryForList("SELECT created_at FROM " + table, LocalDateTime.class)).allSatisfy(time -> {
                assertThat(time).isBetween(before, after); assertThat(time.getNano() % 1_000_000).isZero();
            });
        }
        // Inserting history is not an allocator: no Seat, stock, quota or paid Ticket changes implicitly.
        assertThat(resourcesSnapshot()).isEqualTo(resourcesBefore);
        evidence("day12-gift-history-model", Map.of("operationId", operationId, "type", "ALLOCATE", "status", "SUCCEEDED",
                "quantity", 2, "seatIds", selected.stream().map(seat -> seat.getSeatId()).toList(), "resourceWrites", 0, "paidTicketRows", 0));
    }

    @Test void requestKeyIsUniqueWithinPublisherButReusableByAnotherPublisher() {
        operation(publisher.getId(), "same-key", 1);
        assertThatThrownBy(() -> operation(publisher.getId(), "same-key", 2))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_gift_operation_request");
        operation(otherPublisher.getId(), "same-key", 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation", Integer.class)).isEqualTo(2);
    }

    @Test void requestKeysPreserveCaseAndTrailingCharacters() {
        operation(publisher.getId(), "gift-key", 1); operation(publisher.getId(), "GIFT-key", 1);
        operation(publisher.getId(), "gift-key ", 1); operation(publisher.getId(), "x".repeat(64), 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation", Integer.class)).isEqualTo(4);
        assertThatThrownBy(() -> operation(publisher.getId(), "x".repeat(65), 1)).isInstanceOf(DataAccessException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "     "})
    void blankRequestKeysAreRejected(String key) {
        assertThatThrownBy(() -> operation(publisher.getId(), key, 1)).isInstanceOf(DataAccessException.class).hasMessageContaining("chk_gift_operation_request");
    }

    @ParameterizedTest @CsvSource({"publisher_id,0", "publisher_id,-1", "session_id,0", "session_id,-1", "zone_id,0", "zone_id,-1"})
    void operationLogicalReferenceIdsMustBePositive(String field, long invalid) {
        Long id = operation(publisher.getId(), UUID.randomUUID().toString(), 1);
        assertThatThrownBy(() -> jdbc.update("UPDATE gift_inventory_operation SET " + field + " = ? WHERE operation_id = ?", invalid, id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_gift_operation_ids");
    }

    @ParameterizedTest @ValueSource(ints = {0, -1})
    void quantitiesMustBePositive(int quantity) {
        assertThatThrownBy(() -> operation(publisher.getId(), "quantity", quantity))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_gift_operation_quantity");
    }

    @ParameterizedTest @CsvSource({"operation_type,RELEASE,chk_gift_operation_type", "operation_type,PURCHASE,chk_gift_operation_type",
            "operation_type,allocate,chk_gift_operation_type", "status,succeeded,chk_gift_operation_status",
            "status,PENDING,chk_gift_operation_status", "status,PROCESSING,chk_gift_operation_status", "status,FAILED,chk_gift_operation_status"})
    void onlyAllocateAndCommittedSuccessAreAcceptedInThisSlice(String field, String value, String constraint) {
        Long id = operation(publisher.getId(), "enum", 1);
        assertThatThrownBy(() -> jdbc.update("UPDATE gift_inventory_operation SET " + field + " = ? WHERE operation_id = ?", value, id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    }

    @ParameterizedTest @ValueSource(strings = {"request_id", "publisher_id", "session_id", "zone_id", "operation_type", "quantity", "status", "created_at"})
    void operationFieldsCannotBeNull(String field) {
        Long id = operation(publisher.getId(), "not-null", 1);
        assertThatThrownBy(() -> jdbc.update("UPDATE gift_inventory_operation SET " + field + " = NULL WHERE operation_id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @ParameterizedTest @CsvSource({"operation_id,0", "operation_id,-1", "seat_id,0", "seat_id,-1"})
    void itemReferenceIdsMustBePositive(String field, long invalid) {
        Long id = operation(publisher.getId(), "item-reference", 1);
        Long seatId = seatMapper.findByZoneId(gift.getZoneId()).get(0).getSeatId();
        item(id, seatId);
        assertThatThrownBy(() -> jdbc.update("UPDATE gift_inventory_operation_item SET " + field + " = ? WHERE operation_id = ?", invalid, id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chk_gift_operation_item_ids");
    }

    @ParameterizedTest @ValueSource(strings = {"operation_id", "seat_id", "created_at"})
    void itemFieldsCannotBeNull(String field) {
        Long id = operation(publisher.getId(), "item-null", 1);
        item(id, seatMapper.findByZoneId(gift.getZoneId()).get(0).getSeatId());
        assertThatThrownBy(() -> jdbc.update("UPDATE gift_inventory_operation_item SET " + field + " = NULL WHERE operation_id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test void seatCannotRepeatWithinAnOperationButHistoryIsNotGloballyUniqueBySeat() {
        Long first = operation(publisher.getId(), "history-1", 1); Long second = operation(publisher.getId(), "history-2", 1);
        Long seat = seatMapper.findByZoneId(gift.getZoneId()).get(0).getSeatId(); item(first, seat);
        assertThatThrownBy(() -> item(first, seat)).isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_gift_operation_item_seat");
        item(second, seat);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation_item WHERE seat_id = ?", Integer.class, seat)).isEqualTo(2);
    }

    @Test void positiveLogicalReferencesStillRequireServiceOwnershipAndExistenceChecks() {
        Long id = operation(Long.MAX_VALUE, "logical-only", 1);
        item(id, Long.MAX_VALUE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation_item", Integer.class)).isEqualTo(1);
        // Zero physical FKs is intentional. A future Service must reject these nonexistent references.
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void mainHistoryAndSeatDetailsRollBackTogether(boolean afterItem) {
        var before = resourcesSnapshot();
        assertThatThrownBy(() -> tx(() -> {
            Long id = operation(publisher.getId(), "rollback", 2);
            if (afterItem) { item(id, seatMapper.findByZoneId(gift.getZoneId()).get(0).getSeatId()); }
            throw new IllegalStateException("Injected GIFT history write failure");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Injected GIFT history");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation_item", Integer.class)).isZero();
        assertThat(resourcesSnapshot()).isEqualTo(before);
    }

    @Test void simultaneousSamePublisherRequestKeysHaveOneDatabaseWinner() throws Exception {
        var barrier = new CyclicBarrier(2);
        var a = async(() -> { barrier.await(10, TimeUnit.SECONDS); return tryHistory("concurrent"); });
        var b = async(() -> { barrier.await(10, TimeUnit.SECONDS); return tryHistory("concurrent"); });
        assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_inventory_operation_item", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Integer.class)).isZero();
        evidence("day12-gift-request-key-race", Map.of("committedOperations", 1, "committedItems", 1, "loser", "uq_gift_operation_request", "paidTickets", 0));
    }

    private boolean tryHistory(String key) {
        try {
            return tx(() -> { Long id = operation(publisher.getId(), key, 1); item(id, seatMapper.findByZoneId(gift.getZoneId()).get(0).getSeatId()); return true; });
        } catch (DuplicateKeyException conflict) {
            assertThat(conflict).hasMessageContaining("uq_gift_operation_request"); return false;
        }
    }
    private Long operation(Long operator, String key, int quantity) {
        var generated = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO gift_inventory_operation(publisher_id, session_id, zone_id, request_id, quantity) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, operator); statement.setLong(2, session.getActivitySessionId()); statement.setLong(3, gift.getZoneId());
            statement.setString(4, key); statement.setInt(5, quantity); return statement;
        }, generated);
        return Objects.requireNonNull(generated.getKey()).longValue();
    }
    private void item(Long operationId, Long seatId) {
        assertThat(jdbc.update("INSERT INTO gift_inventory_operation_item(operation_id, seat_id) VALUES (?, ?)", operationId, seatId)).isEqualTo(1);
    }
    private List<String> columns(String table) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position", String.class, table);
    }
    private void index(String table, String index, List<String> columns, boolean nonUnique) {
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? ORDER BY seq_in_index", String.class, table, index)).containsExactlyElementsOf(columns);
        assertThat(jdbc.queryForList("SELECT DISTINCT non_unique FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?", Integer.class, table, index)).containsExactly(nonUnique ? 1 : 0);
    }
    private LocalDateTime now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class); }
    private List<List<Map<String, Object>>> resourcesSnapshot() {
        return List.of("session_zone", "session_seat", "ticket", "ticket_order", "ticket_order_item", "user_session_purchase")
                .stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1")).toList();
    }

    static class UpgradeObservation {
        String beforeVersion, afterVersion; int executed, repeated; LocalDateTime retainedCreatedAt;
        Map<String, String> definitionsBefore, definitionsAfter;
        Map<String, Long> countsBefore, countsAfter;
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean UpgradeObservation upgradeObservation() { return new UpgradeObservation(); }
        @Bean IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties, UpgradeObservation observation) throws Exception {
            var source = new IsolatedQualificationMySqlDataSource(properties, 6, List.of());
            try {
                Path directory = Files.createDirectories(Path.of("target", "gift-migrations", UUID.randomUUID().toString()));
                // V2 contains USE limit_activity_system. Never execute that historical smoke seed in an isolated test.
                for (String name : List.of("V1__create_schema.sql", "V3__create_whitelist_tables.sql", "V4__create_free_reservation_tables.sql",
                        "V5__create_ticket_refund_tables.sql", "V6__add_refund_processing_results.sql", "V7__add_gift_inventory_operations.sql")) {
                    try (var input = new ClassPathResource("db/migration/" + name).getInputStream()) { Files.copy(input, directory.resolve(name)); }
                }
                String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
                var before = Flyway.configure().dataSource(source).locations(location).target(MigrationVersion.fromVersion("6")).cleanDisabled(true).load();
                before.migrate(); observation.beforeVersion = before.info().current().getVersion().toString();
                var jdbc = new JdbcTemplate(source);
                jdbc.update("INSERT INTO app_user(username, password_hash, role, status) VALUES ('gift_v6_upgrade_fixture', 'migration-fixture-not-a-login', 'PUBLISHER', 'ACTIVE')");
                observation.retainedCreatedAt = jdbc.queryForObject("SELECT created_at FROM app_user WHERE username = 'gift_v6_upgrade_fixture'", LocalDateTime.class);
                var tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history' ORDER BY table_name", String.class);
                observation.definitionsBefore = definitions(jdbc, tables); observation.countsBefore = counts(jdbc, tables);
                var after = Flyway.configure().dataSource(source).locations(location).cleanDisabled(true).load();
                observation.executed = after.migrate().migrationsExecuted; after.validate();
                observation.afterVersion = after.info().current().getVersion().toString(); observation.repeated = after.migrate().migrationsExecuted;
                observation.definitionsAfter = definitions(jdbc, tables); observation.countsAfter = counts(jdbc, tables);
                return source;
            } catch (Exception failure) { source.close(); throw failure; }
        }
        private static Map<String, String> definitions(JdbcTemplate jdbc, List<String> tables) {
            Map<String, String> definitions = new LinkedHashMap<>();
            tables.forEach(table -> definitions.put(table, jdbc.queryForMap("SHOW CREATE TABLE " + table).get("Create Table").toString()));
            return definitions;
        }
        private static Map<String, Long> counts(JdbcTemplate jdbc, List<String> tables) {
            Map<String, Long> counts = new LinkedHashMap<>();
            tables.forEach(table -> counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)));
            return counts;
        }
    }
}
