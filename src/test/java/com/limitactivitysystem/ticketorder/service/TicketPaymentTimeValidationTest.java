package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.ticketorder.dto.TicketPaymentResponse;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderItemMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import com.limitactivitysystem.ticketorder.model.TicketOrderItem;
import com.limitactivitysystem.whitelist.service.QualificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/** Exact database-time boundaries; real rollback/lock evidence is covered by the MySQL tests. */
@ExtendWith(MockitoExtension.class)
class TicketPaymentTimeValidationTest {
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 25, 12, 15);
    @Mock private TicketOrderMapper orders;
    @Mock private TicketOrderItemMapper items;
    @Mock private CurrentUserProvider currentUser;
    @Mock private AppUserMapper users;
    @Mock private ActivityMapper activities;
    @Mock private ActivitySessionMapper sessions;
    @Mock private QualificationService qualification;
    @Mock private DatabaseTimeMapper databaseTime;
    @Mock private TicketPaymentResourceService resources;
    @Mock private SeatAllocator seats;
    @Mock private TicketIssuanceService issuance;
    @InjectMocks private TicketPaymentTransactionService payment;
    private TicketOrder order;
    private Activity activity;
    private ActivitySession session;
    private final AtomicReference<LocalDateTime> clock = new AtomicReference<>(DEADLINE.minusSeconds(1));

    @BeforeEach void authenticatedBuyerAndPendingOrder() {
        order = new TicketOrder(); order.setOrderId(1L); order.setBuyerId(2L); order.setSessionId(3L);
        order.setStatus("PENDING_PAYMENT"); order.setExpiresAt(DEADLINE);
        AppUser user = new AppUser(); user.setId(2L); user.setStatus("ACTIVE");
        given(orders.findByIdForUpdate(1L)).willReturn(order);
        given(currentUser.getAuthenticatedUserId()).willReturn(2L);
        given(users.findByIdForShare(2L)).willReturn(user);
        given(databaseTime.currentTime()).willAnswer(invocation -> clock.get());
        activity = new Activity(); activity.setActivityId(4L); activity.setType("PAID"); activity.setStatus("PUBLISHED");
        // Sales already closed, while the order's independent payment deadline is still in the future.
        activity.setOpenTime(DEADLINE.minusHours(1)); activity.setCloseTime(DEADLINE.minusMinutes(1));
        session = new ActivitySession(); session.setActivitySessionId(3L); session.setActivityId(4L);
        session.setStatus("SCHEDULED"); session.setStartTime(DEADLINE.plusHours(1));
        session.setSeatMode("SELECT_SEAT"); session.setMaxTicketsPerUser(4);
    }

    @ParameterizedTest @ValueSource(longs = {-1, 0, 1})
    void expiryIsExclusiveEvenAfterTheSalesWindowClosed(long millisFromExpiry) {
        clock.set(DEADLINE.plusNanos(millisFromExpiry * 1_000_000));
        if (millisFromExpiry < 0) {
            ordinaryScope(); protectedScope(); resourceStage();
            assertThat(payment.pay(1L).status()).isEqualTo("PAID");
        } else {
            denied("ORDER_EXPIRED");
            verifyNoInteractions(qualification, resources, seats);
        }
    }

    @ParameterizedTest @ValueSource(longs = {-1, 0, 1})
    void sessionStartIsExclusiveRegardlessOfRemainingOrderLifetime(long millisFromStart) {
        order.setExpiresAt(DEADLINE.plusMinutes(5)); session.setStartTime(DEADLINE);
        clock.set(DEADLINE.plusNanos(millisFromStart * 1_000_000));
        ordinaryScope();
        if (millisFromStart < 0) {
            protectedScope(); resourceStage();
            assertThat(payment.pay(1L).status()).isEqualTo("PAID");
        } else {
            denied("SESSION_NOT_AVAILABLE");
            verifyNoInteractions(qualification, resources, seats);
        }
    }

    @Test void anOrderExpiringWhileWaitingForEligibilityCannotAcquireQuotaOrSeats() {
        ordinaryScope(); protectedScope(); persistedItems();
        given(qualification.checkAgain(4L, 3L)).willAnswer(invocation -> { clock.set(DEADLINE); return 2L; });
        denied("ORDER_EXPIRED");
        verifyNoInteractions(resources, seats);
    }

    @Test void crossingSessionStartDuringAllocationIsRejectedAtTheFinalTimeCheck() {
        order.setExpiresAt(DEADLINE.plusMinutes(5)); session.setStartTime(DEADLINE);
        ordinaryScope(); protectedScope(); persistedItems();
        given(seats.allocate(eq(3L), anyList())).willAnswer(invocation -> {
            clock.set(DEADLINE); return List.of(new SeatAllocator.Allocation(5L, 6L, 7L));
        });
        denied("SESSION_NOT_AVAILABLE");
    }

    private void ordinaryScope() {
        given(sessions.findById(3L)).willReturn(session); given(activities.findById(4L)).willReturn(activity);
    }
    private void protectedScope() {
        given(qualification.checkAgain(4L, 3L)).willReturn(2L);
        given(activities.findByIdForShare(4L)).willReturn(activity); given(sessions.findByIdForShare(3L)).willReturn(session);
    }
    private void persistedItems() {
        TicketOrderItem item = new TicketOrderItem(); item.setOrderItemId(5L); item.setOrderId(1L); item.setQuantity(1);
        given(items.findByOrderId(1L)).willReturn(List.of(item));
    }
    private void resourceStage() {
        persistedItems(); given(seats.allocate(eq(3L), anyList())).willReturn(List.of(new SeatAllocator.Allocation(5L, 6L, 7L)));
        given(orders.markPaidIfPayable(1L)).willReturn(1);
        given(orders.findById(1L)).willReturn(order);
        given(issuance.response(order)).willReturn(new TicketPaymentResponse(1L, 3L, null, "PAID", clock.get(), List.of()));
    }
    private void denied(String expected) {
        assertThatThrownBy(() -> payment.pay(1L)).isInstanceOfSatisfying(TicketOrderException.class,
                exception -> assertThat(exception.getErrorCode().name()).isEqualTo(expected));
    }
}
