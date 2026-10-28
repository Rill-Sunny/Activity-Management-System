package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(TicketPaymentResourceMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class TicketPaymentResourceMySqlIntegrationTest extends PaymentResourceTestSupport {
    private ActivitySession session;
    private SessionZone zone;

    @BeforeEach void createResourceScope() {
        session = insertSession(insertDraft(publisher, "PAID"));
        zone = insertZone(session, "PAID", 4, 4);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSecondConnectionReallyWaitsOnTheOrderPrimaryKeyUntilCommitOrRollback(boolean rollback) throws Exception {
        var order = newOrder(session, buyer);
        var independent = newOrder(session, buyer);
        var acquired = gate(); var release = gate(); var bAttempted = gate();
        AtomicLong aId = new AtomicLong(); AtomicLong bId = new AtomicLong();
        AtomicInteger completion = new AtomicInteger(-1);
        var a = async(() -> tx(rollback, () -> {
            aId.set(connectionId());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { completion.set(status); }
            });
            var loaded = orders.findByIdForUpdate(order.getOrderId());
            assertThat(loaded.getOrderId()).isEqualTo(order.getOrderId());
            loaded.setStatus("PAID"); // Changing a Java object does not update or lock any additional database row.
            acquired.countDown();
            await(release);
            return loaded.getOrderId();
        }));
        // Rollback is triggered below by a test transaction status rather than an exception swallowed inside SQL code.
        await(acquired);
        assertThat(orders.findById(order.getOrderId()).getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(tx(() -> orders.findByIdForUpdate(independent.getOrderId())).getOrderId())
                .isEqualTo(independent.getOrderId());
        var b = async(() -> tx(() -> {
            bId.set(connectionId()); bAttempted.countDown();
            return orders.findByIdForUpdate(order.getOrderId());
        }));
        await(bAttempted);
        var wait = awaitDatabaseLockWait(bId.get(), aId.get(), "ticket_order");
        assertThat(wait.get("indexName")).isEqualTo("PRIMARY");
        assertThat(wait.get("lockStatus")).isEqualTo("WAITING");
        assertThat(b.isDone()).isFalse();
        release.countDown();
        a.get(10, TimeUnit.SECONDS);
        assertThat(completion.get()).isEqualTo(rollback ? TransactionSynchronization.STATUS_ROLLED_BACK
                : TransactionSynchronization.STATUS_COMMITTED);
        assertThat(b.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo("PENDING_PAYMENT");
        evidence("ticket-order-lock-wait-" + (rollback ? "ROLLBACK" : "COMMIT"),
                Map.of("lockWait", wait, "waiterContinuedAfterTransactionEnd", true,
                        "plainReadCompletedWhileLocked", true, "otherPrimaryKeyWasNotBlocked", true,
                        "holderCompletion", rollback ? "ROLLED_BACK" : "COMMITTED"));
    }

    @Test void lockingAndMutationMappersRejectAutocommitUse() {
        var order = newOrder(session, buyer);
        long seatId = seatMapper.findByZoneId(zone.getZoneId()).get(0).getSeatId();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        for (Runnable operation : List.<Runnable>of(
                () -> orders.findByIdForUpdate(order.getOrderId()), () -> zoneMapper.findByIdForUpdate(zone.getZoneId()),
                () -> purchases.ensureExists(buyer.getId(), session.getActivitySessionId()),
                () -> purchases.tryIncrease(buyer.getId(), session.getActivitySessionId(), 1, 4),
                () -> zoneMapper.decreaseStock(zone.getZoneId(), 1), () -> seatMapper.occupySeat(seatId, zone.getZoneId()),
                () -> resources.occupyPurchaseQuota(buyer.getId(), session.getActivitySessionId(), 1, 4))) {
            assertThatThrownBy(operation::run).isInstanceOf(IllegalTransactionStateException.class);
        }
    }

    @Test void safeInitializationDoesNotResetAnExistingCount() {
        jdbc.update("INSERT INTO user_session_purchase (user_id, session_id, valid_count) VALUES (?, ?, 2)",
                buyer.getId(), session.getActivitySessionId());
        tx(() -> purchases.ensureExists(buyer.getId(), session.getActivitySessionId()));
        assertThat(purchases.findValidCount(buyer.getId(), session.getActivitySessionId())).isEqualTo(2);
        assertThat(counterRows()).isEqualTo(1);
    }

    @Test void simultaneousFirstInitializationCreatesOneZeroCountRow() throws Exception {
        var barrier = new CyclicBarrier(2);
        var ids = new CopyOnWriteArrayList<Long>();
        var a = async(() -> tx(() -> { ids.add(connectionId()); meet(barrier); return purchases.ensureExists(buyer.getId(), session.getActivitySessionId()); }));
        var b = async(() -> tx(() -> { ids.add(connectionId()); meet(barrier); return purchases.ensureExists(buyer.getId(), session.getActivitySessionId()); }));
        a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
        assertThat(counterRows()).isEqualTo(1);
        assertThat(purchases.findValidCount(buyer.getId(), session.getActivitySessionId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentQuotaAcquisitionCannotExceedFourEvenDuringFirstInitialization(boolean existingCount) throws Exception {
        if (existingCount) {
            jdbc.update("INSERT INTO user_session_purchase (user_id, session_id, valid_count) VALUES (?, ?, 2)",
                    buyer.getId(), session.getActivitySessionId());
        }
        var barrier = new CyclicBarrier(2);
        var ids = new CopyOnWriteArrayList<Long>();
        Supplier<String> attempt = () -> {
            try {
                return tx(() -> {
                    ids.add(connectionId()); meet(barrier);
                    resources.occupyPurchaseQuota(buyer.getId(), session.getActivitySessionId(), existingCount ? 2 : 3, 4);
                    return "ACQUIRED";
                });
            } catch (TicketOrderException exception) { return exception.getErrorCode().name(); }
        };
        var a = async(attempt::get); var b = async(attempt::get);
        assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("ACQUIRED", "PURCHASE_LIMIT_EXCEEDED");
        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
        int count = purchases.findValidCount(buyer.getId(), session.getActivitySessionId());
        assertThat(count).isEqualTo(existingCount ? 4 : 3).isLessThanOrEqualTo(4);
        assertThat(counterRows()).isEqualTo(1);
        evidence("quota-race-" + (existingCount ? "existing" : "first-initialization"),
                Map.of("initialCount", existingCount ? 2 : 0, "quantityPerAttempt", existingCount ? 2 : 3,
                        "limit", 4, "finalCount", count, "counterRows", counterRows(), "connections", ids));
    }

    @ParameterizedTest
    @CsvSource({"2,4,1,4", "3,4,0,2", "0,4,0,2", "-1,4,0,2", "2147483647,4,0,2", "1,0,0,2"})
    void quotaConditionalUpdateReturnsAffectedRowsWithoutReadThenWrite(int quantity, int limit, int affected, int result) {
        jdbc.update("INSERT INTO user_session_purchase (user_id, session_id, valid_count) VALUES (?, ?, 2)",
                buyer.getId(), session.getActivitySessionId());
        assertThat(tx(() -> purchases.tryIncrease(buyer.getId(), session.getActivitySessionId(), quantity, limit))).isEqualTo(affected);
        assertThat(purchases.findValidCount(buyer.getId(), session.getActivitySessionId())).isEqualTo(result);
    }

    @Test void failedQuotaAcquisitionRollsBackItsNewInitialization() {
        assertThatThrownBy(() -> tx(() -> { resources.occupyPurchaseQuota(buyer.getId(), session.getActivitySessionId(), 5, 4); return null; }))
                .isInstanceOfSatisfying(TicketOrderException.class,
                        exception -> assertThat(exception.getErrorCode().name()).isEqualTo("PURCHASE_LIMIT_EXCEEDED"));
        assertThat(counterRows()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"1,2,PAID,0,1", "1,1,PAID,1,0", "1,1,GIFT,0,1", "1,0,PAID,0,1", "1,-1,PAID,0,1"})
    void stockConditionalUpdateRejectsOverdrawGiftAndNonPositiveQuantity(int remains, int quantity, String type, int affected, int expected) {
        jdbc.update("UPDATE session_zone SET remains_amount = ?, zone_type = ?, price = ? WHERE zone_id = ?",
                remains, type, type.equals("GIFT") ? 0 : 10, zone.getZoneId());
        assertThat(tx(() -> zoneMapper.decreaseStock(zone.getZoneId(), quantity))).isEqualTo(affected);
        assertThat(zoneMapper.findById(zone.getZoneId()).getRemainsAmount()).isEqualTo(expected);
    }

    @Test void stockServiceRejectsZeroAffectedRows() {
        jdbc.update("UPDATE session_zone SET remains_amount = 1 WHERE zone_id = ?", zone.getZoneId());
        assertThatThrownBy(() -> tx(() -> { resources.decreaseStock(zone.getZoneId(), 2); return null; }))
                .isInstanceOfSatisfying(TicketOrderException.class,
                        exception -> assertThat(exception.getErrorCode().name()).isEqualTo("INSUFFICIENT_INVENTORY"));
        assertThat(zoneMapper.findById(zone.getZoneId()).getRemainsAmount()).isEqualTo(1);
    }

    @Test void twoStockUpdatesCanAcquireTheLastUnitOnlyOnce() throws Exception {
        jdbc.update("UPDATE session_zone SET remains_amount = 1 WHERE zone_id = ?", zone.getZoneId());
        var barrier = new CyclicBarrier(2);
        var a = async(() -> tx(() -> { meet(barrier); return zoneMapper.decreaseStock(zone.getZoneId(), 1); }));
        var b = async(() -> tx(() -> { meet(barrier); return zoneMapper.decreaseStock(zone.getZoneId(), 1); }));
        assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(1, 0);
        assertThat(zoneMapper.findById(zone.getZoneId()).getRemainsAmount()).isZero();
    }

    @Test void twoSeatUpdatesCanOccupyTheSamePhysicalSeatOnlyOnce() throws Exception {
        long seatId = seatMapper.findByZoneId(zone.getZoneId()).get(0).getSeatId();
        var barrier = new CyclicBarrier(2);
        var a = async(() -> tx(() -> { meet(barrier); return seatMapper.occupySeat(seatId, zone.getZoneId()); }));
        var b = async(() -> tx(() -> { meet(barrier); return seatMapper.occupySeat(seatId, zone.getZoneId()); }));
        assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(1, 0);
        assertThat(seatMapper.findById(seatId).getAllocationStatus()).isEqualTo("SOLD");
    }

    @Test void seatUpdateChecksZoneAndCurrentStatus() {
        long seatId = seatMapper.findByZoneId(zone.getZoneId()).get(0).getSeatId();
        assertThat(tx(() -> seatMapper.occupySeat(seatId, Long.MAX_VALUE))).isZero();
        assertThat(seatMapper.findById(seatId).getAllocationStatus()).isEqualTo("AVAILABLE");
        assertThat(tx(() -> seatMapper.occupySeat(seatId, zone.getZoneId()))).isEqualTo(1);
        assertThat(tx(() -> seatMapper.occupySeat(seatId, zone.getZoneId()))).isZero();
    }

    private long counterRows() { return jdbc.queryForObject("SELECT COUNT(*) FROM user_session_purchase", Long.class); }
    private void meet(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception {
            return new IsolatedQualificationMySqlDataSource(properties);
        }
    }
}
