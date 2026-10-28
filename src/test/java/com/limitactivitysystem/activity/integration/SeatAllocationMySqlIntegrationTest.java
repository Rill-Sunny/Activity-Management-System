package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.mapper.SessionSeatMapper;
import com.limitactivitysystem.activity.mapper.SessionZoneMapper;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionSeat;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.mapper.UserSessionPurchaseMapper;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import com.limitactivitysystem.ticketorder.service.SeatAllocator;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(SeatAllocationMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class SeatAllocationMySqlIntegrationTest extends PaymentResourceTestSupport {
    @Autowired private WriteTrace trace;
    private ActivitySession session;
    private SessionZone firstZone;
    private SessionZone secondZone;
    private final List<SessionSeat> firstSeats = new ArrayList<>();
    private final List<SessionSeat> secondSeats = new ArrayList<>();

    @BeforeEach void createInterleavedSeatIdsAcrossTwoZones() {
        session = insertSession(insertDraft(publisher, "PAID"));
        jdbc.update("UPDATE activity_session SET seat_mode = 'AUTO_ASSIGN', max_tickets_per_user = 6 WHERE activity_session_id = ?",
                session.getActivitySessionId());
        firstZone = insertZone(session, "PAID", 3, 0);
        secondZone = insertZone(session, "PAID", 3, 0);
        for (int number = 1; number <= 3; number++) {
            firstSeats.add(insertSeat(firstZone, number));
            secondSeats.add(insertSeat(secondZone, number));
        }
    }

    @Test void selectedAllocationLocksAllZonesThenOccupiesAllSeatsInGlobalIdOrder() {
        mode("SELECT_SEAT");
        var order = newOrder(session, buyer);
        selectedItem(order, secondZone, secondSeats.get(1));
        selectedItem(order, firstZone, firstSeats.get(1));
        selectedItem(order, secondZone, secondSeats.get(0));
        selectedItem(order, firstZone, firstSeats.get(0));
        var probe = new Probe();
        var result = run(order, probe);
        var expectedIds = List.of(firstSeats.get(0).getSeatId(), secondSeats.get(0).getSeatId(),
                firstSeats.get(1).getSeatId(), secondSeats.get(1).getSeatId());
        assertThat(result).extracting(SeatAllocator.Allocation::seatId).containsExactlyElementsOf(expectedIds);
        assertThat(probe.events).containsExactly("ORDER", "QUOTA_INIT", "QUOTA_TAKE",
                "ZONE:" + firstZone.getZoneId(), "ZONE:" + secondZone.getZoneId(),
                "STOCK:" + firstZone.getZoneId(), "STOCK:" + secondZone.getZoneId(),
                "SEAT:" + expectedIds.get(0), "SEAT:" + expectedIds.get(1),
                "SEAT:" + expectedIds.get(2), "SEAT:" + expectedIds.get(3));
        assertThat(zoneMapper.findById(firstZone.getZoneId()).getRemainsAmount()).isEqualTo(1);
        assertThat(zoneMapper.findById(secondZone.getZoneId()).getRemainsAmount()).isEqualTo(1);
        assertResourcePhaseOnly(order, 4);
    }

    @Test void automaticAllocationUsesPriorityForCandidatesButIdOrderForActualWrites() {
        var order = newOrder(session, buyer);
        automaticItem(order, firstZone, 2, false);
        var probe = new Probe();
        var result = run(order, probe);
        assertThat(result).extracting(SeatAllocator.Allocation::seatId)
                .containsExactly(firstSeats.get(1).getSeatId(), firstSeats.get(2).getSeatId());
        assertThat(probe.seatIds).isSorted();
        assertResourcePhaseOnly(order, 2);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void nonAdjacentSeatsAreUsedOnlyWhenTheSavedOrderAcceptsThem(boolean accepts) {
        jdbc.update("UPDATE session_seat SET allocation_status = 'SOLD' WHERE seat_id = ?", firstSeats.get(1).getSeatId());
        jdbc.update("UPDATE session_zone SET remains_amount = 2 WHERE zone_id = ?", firstZone.getZoneId());
        var order = newOrder(session, buyer);
        automaticItem(order, firstZone, 2, accepts);
        if (accepts) {
            assertThat(run(order, new Probe())).extracting(SeatAllocator.Allocation::seatId)
                    .containsExactly(firstSeats.get(0).getSeatId(), firstSeats.get(2).getSeatId());
            assertThat(zoneMapper.findById(firstZone.getZoneId()).getRemainsAmount()).isZero();
        } else { rejected(order, "ADJACENT_SEATS_UNAVAILABLE"); }
    }

    @Test void insufficientPhysicalSeatsRollBackStockAndQuotaEvenWhenTheCounterPrecheckPassed() {
        jdbc.update("UPDATE session_seat SET allocation_status = 'SOLD' WHERE seat_id = ?", firstSeats.get(1).getSeatId());
        var order = newOrder(session, buyer);
        automaticItem(order, firstZone, 3, true);
        rejected(order, "SEAT_REQUIREMENT_NOT_SATISFIED");
    }

    @Test void multipleItemsInOneZoneNeverReceiveTheSameSeat() {
        var order = newOrder(session, buyer);
        automaticItem(order, firstZone, 1, false);
        automaticItem(order, firstZone, 1, true);
        var result = run(order, new Probe());
        assertThat(result).extracting(SeatAllocator.Allocation::seatId).hasSize(2).doesNotHaveDuplicates();
        assertThat(result).extracting(SeatAllocator.Allocation::orderItemId).doesNotHaveDuplicates();
    }

    @Test void giftZonesAreRejectedBeforeAnyStockOrSeatWrite() {
        jdbc.update("UPDATE session_zone SET zone_type = 'GIFT', price = 0 WHERE zone_id = ?", firstZone.getZoneId());
        var order = newOrder(session, buyer);
        automaticItem(order, firstZone, 1, false);
        rejected(order, "GIFT_ZONE_NOT_PURCHASABLE");
    }

    @Test void zonesFromAnotherSessionAreRejected() {
        var otherSession = insertSession(insertDraft(otherPublisher, "PAID"));
        var otherZone = insertZone(otherSession, "PAID", 2, 2);
        var order = newOrder(session, buyer);
        automaticItem(order, otherZone, 1, false);
        rejected(order, "INVALID_ZONE_FOR_SESSION");
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING", "WRONG_ZONE", "SOLD"})
    void selectedAllocationRechecksPhysicalSeatExistenceOwnershipAndAvailability(String scenario) {
        mode("SELECT_SEAT");
        var order = newOrder(session, buyer);
        Long id = scenario.equals("MISSING") ? Long.MAX_VALUE : scenario.equals("WRONG_ZONE")
                ? secondSeats.get(0).getSeatId() : firstSeats.get(0).getSeatId();
        if (scenario.equals("SOLD")) { jdbc.update("UPDATE session_seat SET allocation_status = 'SOLD' WHERE seat_id = ?", id); }
        newItem(order, firstZone.getZoneId(), "SELECT_SEAT", 1, id, false);
        rejected(order, scenario.equals("SOLD") ? "SEAT_NOT_AVAILABLE" : "INVALID_SEAT_RELATION");
    }

    @Test void mismatchedModesAreRejectedUsingTheFrozenSessionConfiguration() {
        mode("SELECT_SEAT");
        var order = newOrder(session, buyer);
        automaticItem(order, firstZone, 1, false);
        rejected(order, "INVALID_ALLOCATION_MODE");
    }

    @Test void aFailureAfterAllocationReturnsRollsBackEveryResourceChange() {
        var order = newOrder(session, buyer);
        automaticItem(order, firstZone, 2, false);
        var before = resourceSnapshot();
        assertThatThrownBy(() -> tx(() -> {
            executeResourcePhase(order, new Probe());
            throw new IllegalStateException("test failure after seat allocation");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(resourceSnapshot()).isEqualTo(before);
        assertThat(orders.findById(order.getOrderId()).getStatus()).isEqualTo("PENDING_PAYMENT");
    }

    @Test void conditionalSeatFailureRollsBackAnEarlierSuccessfulSeatWriteAndAllQuotaAndStock() throws Exception {
        mode("SELECT_SEAT");
        var order = newOrder(session, buyer);
        selectedItem(order, firstZone, firstSeats.get(0));
        selectedItem(order, firstZone, firstSeats.get(1));
        var probe = new Probe(); probe.beforeSeat = gate(); probe.releaseSeat = gate();
        var action = async(() -> run(order, probe));
        await(probe.beforeSeat);
        // An independent raw conditional writer simulates loss after selection; the production Zone protocol is stricter.
        assertThat(tx(() -> seatMapper.occupySeat(firstSeats.get(1).getSeatId(), firstZone.getZoneId()))).isEqualTo(1);
        probe.releaseSeat.countDown();
        assertThatThrownBy(() -> action.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                exception -> assertThat(((TicketOrderException) exception.getCause()).getErrorCode().name()).isEqualTo("SEAT_NOT_AVAILABLE"));
        assertThat(probe.successfulSeatWrites.get()).isEqualTo(1);
        assertThat(seatMapper.findById(firstSeats.get(0).getSeatId()).getAllocationStatus()).isEqualTo("AVAILABLE");
        assertThat(seatMapper.findById(firstSeats.get(1).getSeatId()).getAllocationStatus()).isEqualTo("SOLD");
        assertThat(zoneMapper.findById(firstZone.getZoneId()).getRemainsAmount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_session_purchase", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class)).isZero();
    }

    @Test void automaticCandidatesAreReadOnlyAfterTheContendedZoneLockAndAreFreshAfterTheWinnerCommits() throws Exception {
        var first = newOrder(session, buyer);
        automaticItem(first, firstZone, 2, false);
        var second = newOrder(session, publisher);
        automaticItem(second, firstZone, 1, false);
        var holder = new Probe(); holder.afterAvailable = gate(); holder.releaseAvailable = gate();
        var waiter = new Probe(); var attempted = gate();
        var a = async(() -> run(first, holder));
        await(holder.afterAvailable);
        var b = async(() -> tx(() -> {
            assertThat(seatMapper.findAvailableByZoneId(firstZone.getZoneId())).hasSize(3); // earlier cache entry
            waiter.connection.set(connectionId()); attempted.countDown();
            return executeResourcePhase(second, waiter);
        }));
        await(attempted);
        var waiting = awaitDatabaseLockWait(waiter.connection.get(), holder.connection.get(), "session_zone");
        assertThat(b.isDone()).isFalse();
        holder.releaseAvailable.countDown();
        var allocated = new ArrayList<>(a.get(10, TimeUnit.SECONDS));
        allocated.addAll(b.get(10, TimeUnit.SECONDS));
        assertThat(allocated).extracting(SeatAllocator.Allocation::seatId).hasSize(3).doesNotHaveDuplicates();
        assertThat(waiter.availableCounts).containsExactly(1);
        assertThat(zoneMapper.findById(firstZone.getZoneId()).getRemainsAmount()).isZero();
        evidence("auto-seat-zone-coordination", Map.of("lockWait", waiting,
                "earlierWaiterAvailableCount", 3, "availableCountAfterZoneLock", waiter.availableCounts,
                "allocatedSeatIds", allocated.stream().map(SeatAllocator.Allocation::seatId).toList(),
                "finalRemains", 0));
    }

    @Test void selectedSeatCompetitorsCoordinateOnTheZoneAndOnlyOneCanAcquireTheSeat() throws Exception {
        mode("SELECT_SEAT");
        var first = newOrder(session, buyer); selectedItem(first, firstZone, firstSeats.get(0));
        var second = newOrder(session, publisher); selectedItem(second, firstZone, firstSeats.get(0));
        var holder = new Probe(); holder.beforeSeat = gate(); holder.releaseSeat = gate();
        var waiter = new Probe(); var attempted = gate();
        var a = async(() -> run(first, holder));
        await(holder.beforeSeat);
        var b = async(() -> tx(() -> {
            waiter.connection.set(connectionId()); attempted.countDown();
            return executeResourcePhase(second, waiter);
        }));
        await(attempted);
        awaitDatabaseLockWait(waiter.connection.get(), holder.connection.get(), "session_zone");
        holder.releaseSeat.countDown();
        assertThat(a.get(10, TimeUnit.SECONDS)).hasSize(1);
        assertThatThrownBy(() -> b.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                exception -> assertThat(((TicketOrderException) exception.getCause()).getErrorCode().name()).isEqualTo("SEAT_NOT_AVAILABLE"));
        assertThat(zoneMapper.findById(firstZone.getZoneId()).getRemainsAmount()).isEqualTo(2);
        assertThat(purchases.findValidCount(publisher.getId(), session.getActivitySessionId())).isZero();
    }

    @Test void allocationRequiresTheExistingExplicitReadCommittedTransaction() {
        var order = newOrder(session, buyer);
        automaticItem(order, firstZone, 1, false);
        var saved = orderItems.findByOrderId(order.getOrderId());
        assertThatThrownBy(() -> allocator.allocate(session.getActivitySessionId(), saved))
                .isInstanceOf(IllegalTransactionStateException.class);
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> transaction.execute(status -> allocator.allocate(session.getActivitySessionId(), saved)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private SessionSeat insertSeat(SessionZone zone, int number) {
        var seat = new SessionSeat(); seat.setZoneId(zone.getZoneId()); seat.setRowNo(1); seat.setSeatNo(number);
        seat.setAllocationPriority(4 - number);
        assertThat(seatMapper.insert(seat)).isEqualTo(1);
        return seat;
    }
    private void mode(String mode) { jdbc.update("UPDATE activity_session SET seat_mode = ? WHERE activity_session_id = ?", mode, session.getActivitySessionId()); }
    private void automaticItem(TicketOrder order, SessionZone zone, int quantity, boolean accepts) {
        newItem(order, zone.getZoneId(), "AUTO_ASSIGN", quantity, null, accepts);
    }
    private void selectedItem(TicketOrder order, SessionZone zone, SessionSeat seat) {
        newItem(order, zone.getZoneId(), "SELECT_SEAT", 1, seat.getSeatId(), false);
    }
    private List<SeatAllocator.Allocation> run(TicketOrder order, Probe probe) { return tx(() -> executeResourcePhase(order, probe)); }
    private List<SeatAllocator.Allocation> executeResourcePhase(TicketOrder order, Probe probe) {
        trace.current.set(probe);
        try {
            probe.connection.set(connectionId());
            orders.findByIdForUpdate(order.getOrderId());
            var saved = orderItems.findByOrderId(order.getOrderId());
            int quantity = saved.stream().mapToInt(item -> item.getQuantity()).sum();
            resources.occupyPurchaseQuota(order.getBuyerId(), order.getSessionId(), quantity, 6);
            return allocator.allocate(order.getSessionId(), saved);
        } finally { trace.current.remove(); }
    }
    private void rejected(TicketOrder order, String code) {
        var before = resourceSnapshot();
        assertThatThrownBy(() -> run(order, new Probe())).isInstanceOfSatisfying(TicketOrderException.class,
                exception -> assertThat(exception.getErrorCode().name()).isEqualTo(code));
        assertThat(resourceSnapshot()).isEqualTo(before);
    }
    private List<List<Map<String, Object>>> resourceSnapshot() {
        return List.of("user_session_purchase", "session_zone", "session_seat", "ticket").stream()
                .map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1")).toList();
    }
    private void assertResourcePhaseOnly(TicketOrder order, int validCount) {
        assertThat(orders.findById(order.getOrderId()).getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class)).isZero();
        assertThat(purchases.findValidCount(order.getBuyerId(), order.getSessionId())).isEqualTo(validCount);
    }

    static class Probe {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<Long> seatIds = new CopyOnWriteArrayList<>();
        final List<Integer> availableCounts = new CopyOnWriteArrayList<>();
        final AtomicLong connection = new AtomicLong();
        final AtomicInteger successfulSeatWrites = new AtomicInteger();
        CountDownLatch afterAvailable; CountDownLatch releaseAvailable;
        CountDownLatch beforeSeat; CountDownLatch releaseSeat;
    }

    @Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})})
    static class WriteTrace implements Interceptor {
        final ThreadLocal<Probe> current = new ThreadLocal<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            Probe probe = current.get();
            if (probe == null) { return invocation.proceed(); }
            String id = ((MappedStatement) invocation.getArgs()[0]).getId();
            var parameters = invocation.getArgs()[1] instanceof Map<?, ?> map ? map : Map.of();
            if (id.equals(TicketOrderMapper.class.getName() + ".findByIdForUpdate")) { probe.events.add("ORDER"); }
            if (id.equals(UserSessionPurchaseMapper.class.getName() + ".ensureExists")) { probe.events.add("QUOTA_INIT"); }
            if (id.equals(UserSessionPurchaseMapper.class.getName() + ".tryIncrease")) { probe.events.add("QUOTA_TAKE"); }
            if (id.equals(SessionZoneMapper.class.getName() + ".findByIdForUpdate")) { probe.events.add("ZONE:" + parameters.get("zoneId")); }
            if (id.equals(SessionZoneMapper.class.getName() + ".decreaseStock")) { probe.events.add("STOCK:" + parameters.get("zoneId")); }
            boolean occupy = id.equals(SessionSeatMapper.class.getName() + ".occupySeat");
            if (occupy) {
                if (probe.beforeSeat != null) { probe.beforeSeat.countDown(); await(probe.releaseSeat); probe.beforeSeat = null; }
                probe.seatIds.add((Long) parameters.get("seatId"));
                probe.events.add("SEAT:" + parameters.get("seatId"));
            }
            Object result = invocation.proceed();
            if (occupy && Integer.valueOf(1).equals(result)) { probe.successfulSeatWrites.incrementAndGet(); }
            if (id.equals(SessionSeatMapper.class.getName() + ".findAvailableByZoneId")) {
                probe.availableCounts.add(((List<?>) result).size());
                if (probe.afterAvailable != null) { probe.afterAvailable.countDown(); await(probe.releaseAvailable); probe.afterAvailable = null; }
            }
            return result;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception { return new IsolatedQualificationMySqlDataSource(properties); }
        @Bean WriteTrace writeTrace() { return new WriteTrace(); }
    }
}
