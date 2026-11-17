package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderItemMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.mapper.UserSessionPurchaseMapper;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import com.limitactivitysystem.ticketorder.model.TicketOrderItem;
import com.limitactivitysystem.ticketorder.service.SeatAllocator;
import com.limitactivitysystem.ticketorder.service.TicketPaymentResourceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

// Component tests use real transactions in their own random schema; they do not implement a payment endpoint.
abstract class PaymentResourceTestSupport extends ActivityMySqlTestSupport {
    @Autowired protected TicketOrderMapper orders;
    @Autowired protected TicketOrderItemMapper orderItems;
    @Autowired protected UserSessionPurchaseMapper purchases;
    @Autowired protected TicketPaymentResourceService resources;
    @Autowired protected SeatAllocator allocator;
    private ExecutorService workers;
    private final List<CountDownLatch> gates = new ArrayList<>();

    @BeforeEach void startPaymentWorkers() { workers = Executors.newFixedThreadPool(2); }

    @AfterEach void stopWorkersBeforeRemovingOnlyTheOwnedResourceFixtures() throws Exception {
        gates.forEach(CountDownLatch::countDown);
        workers.shutdownNow();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).matches("qual_it_[a-f0-9]{32}");
        // These committed derivatives must leave with their source fixtures, before the next invocation.
        // Historical V4/V7 schema tests intentionally predate messaging; do not change their migration targets.
        var tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class);
        for (String table : List.of("ticket_qualification_after_sale_task", "notification", "consumer_failure",
                "consumer_process", "outbox_event")) {
            if (tables.contains(table)) { jdbc.update("DELETE FROM " + table); }
        }
        jdbc.update("DELETE FROM ticket");
        jdbc.update("DELETE FROM ticket_order_item");
        jdbc.update("DELETE FROM ticket_order");
        jdbc.update("DELETE FROM user_session_purchase");
    }

    protected <T> T tx(Supplier<T> body) {
        return tx(false, body);
    }

    protected <T> T tx(boolean rollback, Supplier<T> body) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> {
            T result = body.get();
            if (rollback) { status.setRollbackOnly(); }
            return result;
        });
    }

    protected <T> Future<T> async(Callable<T> body) { return workers.submit(body); }
    protected CountDownLatch gate() {
        CountDownLatch latch = new CountDownLatch(1);
        gates.add(latch);
        return latch;
    }
    protected static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
    protected long connectionId() { return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class); }

    protected Map<String, Object> awaitDatabaseLockWait(long waiter, long holder, String table) {
        assertThat(waiter).isPositive().isNotEqualTo(holder);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            var rows = jdbc.queryForList("""
                    SELECT r.PROCESSLIST_ID AS waitingConnectionId, b.PROCESSLIST_ID AS blockingConnectionId,
                           l.OBJECT_NAME AS objectName, l.INDEX_NAME AS indexName,
                           l.LOCK_TYPE AS lockType, l.LOCK_MODE AS lockMode, l.LOCK_STATUS AS lockStatus
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.threads r ON r.THREAD_ID = w.REQUESTING_THREAD_ID
                    JOIN performance_schema.threads b ON b.THREAD_ID = w.BLOCKING_THREAD_ID
                    JOIN performance_schema.data_locks l
                      ON l.ENGINE = w.ENGINE AND l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                    WHERE r.PROCESSLIST_ID = ? AND b.PROCESSLIST_ID = ?
                      AND l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = ?
                    """, waiter, holder, table);
            if (!rows.isEmpty()) { return rows.get(0); }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            if (Thread.currentThread().isInterrupted()) { throw new IllegalStateException("Lock observation interrupted"); }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected a real InnoDB lock wait between the two test connections on " + table);
    }

    protected void evidence(String name, Map<String, Object> observation) throws Exception {
        Path path = Path.of("target", "verification", name + ".json");
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), Map.of(
                "observedAtDatabaseTime", jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class),
                "observation", observation));
    }

    protected TicketOrder newOrder(ActivitySession session, AppUser actor) {
        TicketOrder order = new TicketOrder();
        order.setBuyerId(actor.getId()); order.setSessionId(session.getActivitySessionId());
        order.setRequestId(UUID.randomUUID().toString()); order.setTotalAmount(BigDecimal.TEN);
        assertThat(orders.insert(order)).isEqualTo(1);
        return order;
    }

    protected TicketOrderItem newItem(TicketOrder order, Long zoneId, String mode, int quantity, Long seatId, boolean accept) {
        TicketOrderItem item = new TicketOrderItem();
        item.setOrderId(order.getOrderId()); item.setZoneId(zoneId); item.setAllocationMode(mode);
        item.setQuantity(quantity); item.setRequestedSeatId(seatId); item.setAcceptNonAdjacent(accept);
        item.setUnitPrice(BigDecimal.TEN);
        assertThat(orderItems.insert(item)).isEqualTo(1);
        jdbc.update("""
                UPDATE ticket_order SET total_amount =
                    (SELECT SUM(quantity * unit_price) FROM ticket_order_item WHERE order_id = ?)
                WHERE order_id = ?
                """, order.getOrderId(), order.getOrderId());
        return item;
    }
}
