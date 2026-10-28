package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderItemMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import com.limitactivitysystem.ticketorder.model.TicketOrderItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Reuses the isolated schema and Activity fixtures; only the two new order Mappers are under test.
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(TicketOrderMapperMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class TicketOrderMapperMySqlIntegrationTest extends ActivityMySqlTestSupport {

    @Autowired private TicketOrderMapper orders;
    @Autowired private TicketOrderItemMapper items;
    private ActivitySession session;
    private SessionZone zone;

    @BeforeEach
    void createOrderScope() {
        session = insertSession(insertDraft(publisher, "PAID"));
        jdbc.update("UPDATE activity_session SET seat_mode = 'AUTO_ASSIGN' WHERE activity_session_id = ?",
                session.getActivitySessionId());
        zone = insertZone(session, "PAID", 10, 2);
    }

    @AfterEach
    void removeOrdersBeforeTheOwnedParentFixtures() {
        // Both tables belong to this class's random schema, never the configured business schema.
        jdbc.update("DELETE FROM ticket_order_item");
        jdbc.update("DELETE FROM ticket_order");
    }

    @Test
    void insertUsesDatabaseTimesAndPendingStateInsteadOfReadOnlyModelValues() {
        TicketOrder order = order(buyer.getId(), "database-fields", "20.00");
        order.setStatus("PAID");
        order.setCreatedAt(LocalDateTime.of(2000, 1, 1, 0, 0));
        order.setExpiresAt(LocalDateTime.of(2000, 1, 1, 0, 1));
        order.setPaidAt(LocalDateTime.of(2000, 1, 1, 0, 0));
        order.setClosedReason("must-not-be-inserted");
        LocalDateTime before = databaseNow();

        assertThat(orders.insert(order)).isEqualTo(1);
        assertThat(order.getOrderId()).isPositive();
        TicketOrder stored = orders.findById(order.getOrderId());

        assertThat(stored.getBuyerId()).isEqualTo(buyer.getId());
        assertThat(stored.getSessionId()).isEqualTo(session.getActivitySessionId());
        assertThat(stored.getRequestId()).isEqualTo("database-fields");
        assertThat(stored.getTotalAmount()).isEqualByComparingTo("20.00");
        assertThat(stored.getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(stored.getCreatedAt()).isBetween(before, databaseNow());
        assertThat(stored.getExpiresAt()).isEqualTo(stored.getCreatedAt().plusMinutes(15));
        assertThat(stored.getPaidAt()).isNull();
        assertThat(stored.getClosedReason()).isNull();
        assertThat(orders.findByBuyerIdAndRequestId(buyer.getId(), "database-fields"))
                .usingRecursiveComparison().isEqualTo(stored);
    }

    @Test
    void sameRequestIdBelongsToEachBuyerSeparately() {
        TicketOrder first = insert(order(buyer.getId(), "same-request", "10.00"));
        TicketOrder second = insert(order(otherPublisher.getId(), "same-request", "30.00"));

        assertThat(orders.findByBuyerIdAndRequestId(buyer.getId(), "same-request").getOrderId())
                .isEqualTo(first.getOrderId()).isNotEqualTo(second.getOrderId());
        assertThat(orders.findByBuyerIdAndRequestId(otherPublisher.getId(), "same-request").getOrderId())
                .isEqualTo(second.getOrderId());
        assertThat(orders.findByBuyerIdAndRequestId(publisher.getId(), "same-request")).isNull();
        assertThat(orders.findByBuyerIdAndRequestId(buyer.getId(), "missing-request")).isNull();
        assertThat(orders.findById(Long.MAX_VALUE)).isNull();
    }

    @Test
    void lookupBindsTheRequestIdAsData() {
        String requestId = "request' OR 1=1 --";
        TicketOrder own = insert(order(buyer.getId(), requestId, "10.00"));
        insert(order(otherPublisher.getId(), requestId, "20.00"));

        assertThat(orders.findByBuyerIdAndRequestId(buyer.getId(), requestId).getOrderId())
                .isEqualTo(own.getOrderId());
        assertThat(orders.findByBuyerIdAndRequestId(buyer.getId(), "' OR 1=1 --")).isNull();
    }

    @Test
    void duplicateBuyerRequestIsRejectedByTheRealUniqueConstraint() {
        TicketOrder original = insert(order(buyer.getId(), "duplicate-request", "10.00"));

        assertThatThrownBy(() -> orders.insert(order(buyer.getId(), "duplicate-request", "20.00")))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class)).isEqualTo(1);
        assertThat(orders.findByBuyerIdAndRequestId(buyer.getId(), "duplicate-request").getOrderId())
                .isEqualTo(original.getOrderId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1234.56", "9999999999.99"})
    void orderAmountsRoundTripAsDecimals(String amount) {
        TicketOrder order = insert(order(buyer.getId(), UUID.randomUUID().toString(), amount));
        assertThat(orders.findById(order.getOrderId()).getTotalAmount()).isEqualByComparingTo(amount);
        assertThat(orders.findByBuyerIdAndRequestId(buyer.getId(), order.getRequestId()).getTotalAmount())
                .isEqualByComparingTo(amount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "CLOSED", "STARTED", "NOT_OPEN"})
    void conditionalCreationInsertUsesItsOwnDatabaseTimestamp(String state) {
        LocalDateTime now = databaseNow();
        TicketOrder order = order(buyer.getId(), "conditional-time", "10.00");
        LocalDateTime open = state.equals("NOT_OPEN") ? now.plusHours(1) : now.minusHours(2);
        LocalDateTime close = state.equals("CLOSED") ? now.minusHours(1) : now.plusHours(2);
        LocalDateTime start = state.equals("STARTED") ? now.minusHours(1) : now.plusHours(3);
        int rows = orders.insertDuringCreationWindow(order, open, close, start);
        if (state.equals("OPEN")) {
            assertThat(rows).isEqualTo(1);
            assertThat(order.getOrderId()).isPositive();
            TicketOrder stored = orders.findById(order.getOrderId());
            assertThat(stored.getCreatedAt()).isAfterOrEqualTo(open).isBefore(close).isBefore(start);
            assertThat(stored.getExpiresAt()).isEqualTo(stored.getCreatedAt().plusMinutes(15));
        } else {
            assertThat(rows).isZero();
            assertThat(orders.findByBuyerIdAndRequestId(buyer.getId(), "conditional-time")).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT_SEAT", "AUTO_ASSIGN"})
    void singleItemInsertMapsEveryFieldAndFillsTheGeneratedId(String mode) {
        if ("SELECT_SEAT".equals(mode)) {
            jdbc.update("UPDATE activity_session SET seat_mode = 'SELECT_SEAT' WHERE activity_session_id = ?",
                    session.getActivitySessionId());
        }
        TicketOrder order = insert(order(buyer.getId(), "single-item", "20.00"));
        Long seatId = "SELECT_SEAT".equals(mode)
                ? seatMapper.findByZoneId(zone.getZoneId()).get(0).getSeatId() : null;
        TicketOrderItem item = item(order, zone, mode, seatId == null ? 2 : 1, seatId == null, seatId);
        var resourcesBefore = inventorySnapshot();

        assertThat(items.insert(item)).isEqualTo(1);
        assertThat(item.getOrderItemId()).isPositive();
        assertThat(items.findByOrderId(order.getOrderId())).singleElement()
                .usingRecursiveComparison().isEqualTo(item);
        assertThat(inventorySnapshot()).isEqualTo(resourcesBefore);
    }

    @Test
    void batchInsertFillsAllIdsAndFindByOrderIdKeepsTheOrderBoundary() {
        SessionZone secondZone = insertZone(session, "PAID", 10, 0);
        // insertZone uses a unique name; different server prices exercise DECIMAL mapping.
        jdbc.update("UPDATE session_zone SET price = 23.45 WHERE zone_id = ?", secondZone.getZoneId());
        secondZone.setPrice(new BigDecimal("23.45"));
        TicketOrder order = insert(order(buyer.getId(), "batch-items", "100.35"));
        List<TicketOrderItem> batch = List.of(
                item(order, zone, "AUTO_ASSIGN", 2, false, null),
                item(order, secondZone, "AUTO_ASSIGN", 3, true, null),
                item(order, zone, "AUTO_ASSIGN", 1, false, null));
        TicketOrder other = insert(order(buyer.getId(), "another-order", "10.00"));
        assertThat(items.insert(item(other, zone, "AUTO_ASSIGN", 1, false, null))).isEqualTo(1);
        var resourcesBefore = inventorySnapshot();

        assertThat(items.batchInsert(batch)).isEqualTo(3);

        assertThat(batch).extracting(TicketOrderItem::getOrderItemId)
                .doesNotContainNull().doesNotHaveDuplicates().isSorted();
        assertThat(batch).allSatisfy(item -> assertThat(item.getOrderItemId()).isPositive());
        assertThat(items.findByOrderId(order.getOrderId())).usingRecursiveComparison().isEqualTo(batch);
        assertThat(items.findByOrderId(other.getOrderId())).hasSize(1);
        assertThat(inventorySnapshot()).isEqualTo(resourcesBefore);
    }

    @Test
    void missingItemsReturnAnEmptyList() {
        TicketOrder order = insert(order(buyer.getId(), "no-items", "10.00"));
        assertThat(items.findByOrderId(order.getOrderId())).isEmpty();
        assertThat(items.findByOrderId(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void aFailedLaterItemRollsBackTheHeaderAndTheEarlierItem() {
        jdbc.update("UPDATE activity_session SET seat_mode = 'SELECT_SEAT' WHERE activity_session_id = ?",
                session.getActivitySessionId());
        Long seatId = seatMapper.findByZoneId(zone.getZoneId()).get(0).getSeatId();
        var resourcesBefore = inventorySnapshot();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            TicketOrder order = insert(order(buyer.getId(), "rolled-back", "20.00"));
            items.insert(item(order, zone, "SELECT_SEAT", 1, false, seatId));
            items.insert(item(order, zone, "SELECT_SEAT", 1, false, seatId));
        })).isInstanceOf(DuplicateKeyException.class);

        assertThat(orders.findByBuyerIdAndRequestId(buyer.getId(), "rolled-back")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order_item", Long.class)).isZero();
        assertThat(inventorySnapshot()).isEqualTo(resourcesBefore);
    }

    @Test
    void aDuplicateSeatInOneBatchDoesNotLeaveAnEarlierRow() {
        jdbc.update("UPDATE activity_session SET seat_mode = 'SELECT_SEAT' WHERE activity_session_id = ?",
                session.getActivitySessionId());
        TicketOrder order = insert(order(buyer.getId(), "invalid-batch", "20.00"));
        Long seatId = seatMapper.findByZoneId(zone.getZoneId()).get(0).getSeatId();
        var resourcesBefore = inventorySnapshot();

        assertThatThrownBy(() -> items.batchInsert(List.of(
                item(order, zone, "SELECT_SEAT", 1, false, seatId),
                item(order, zone, "SELECT_SEAT", 1, false, seatId))))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(items.findByOrderId(order.getOrderId())).isEmpty();
        assertThat(inventorySnapshot()).isEqualTo(resourcesBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "CLOSED"})
    void queriesMapPersistedPaidAndClosedFields(String status) {
        TicketOrder order = insert(order(buyer.getId(), "read-state", "10.00"));
        if ("PAID".equals(status)) {
            jdbc.update("UPDATE ticket_order SET status = 'PAID', paid_at = created_at WHERE order_id = ?",
                    order.getOrderId());
        } else {
            jdbc.update("UPDATE ticket_order SET status = 'CLOSED', closed_reason = 'TEST_CLOSED' WHERE order_id = ?",
                    order.getOrderId());
        }

        TicketOrder stored = orders.findById(order.getOrderId());
        assertThat(stored.getStatus()).isEqualTo(status);
        if ("PAID".equals(status)) {
            assertThat(stored.getPaidAt()).isEqualTo(stored.getCreatedAt());
            assertThat(stored.getClosedReason()).isNull();
        } else {
            assertThat(stored.getPaidAt()).isNull();
            assertThat(stored.getClosedReason()).isEqualTo("TEST_CLOSED");
        }
        assertThat(orders.findByBuyerIdAndRequestId(buyer.getId(), "read-state"))
                .usingRecursiveComparison().isEqualTo(stored);
    }

    private TicketOrder order(Long buyerId, String requestId, String amount) {
        TicketOrder order = new TicketOrder();
        order.setBuyerId(buyerId);
        order.setSessionId(session.getActivitySessionId());
        order.setRequestId(requestId);
        order.setTotalAmount(new BigDecimal(amount));
        return order;
    }

    private TicketOrder insert(TicketOrder order) {
        assertThat(orders.insert(order)).isEqualTo(1);
        assertThat(order.getOrderId()).isPositive();
        return order;
    }

    private TicketOrderItem item(TicketOrder order, SessionZone zone, String mode, int quantity,
                                 boolean acceptNonAdjacent, Long seatId) {
        TicketOrderItem item = new TicketOrderItem();
        item.setOrderId(order.getOrderId());
        item.setZoneId(zone.getZoneId());
        item.setAllocationMode(mode);
        item.setRequestedSeatId(seatId);
        item.setQuantity(quantity);
        item.setUnitPrice(zone.getPrice());
        item.setAcceptNonAdjacent(acceptNonAdjacent);
        return item;
    }

    private LocalDateTime databaseNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
    }

    private Map<String, List<Map<String, Object>>> inventorySnapshot() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of("session_zone", "session_seat", "ticket", "user_session_purchase")) {
            rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1"));
        }
        return rows;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception {
            return new IsolatedQualificationMySqlDataSource(properties);
        }
    }
}
