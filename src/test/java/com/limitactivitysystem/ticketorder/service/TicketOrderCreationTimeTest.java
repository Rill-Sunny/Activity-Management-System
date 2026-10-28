package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.mapper.SessionSeatMapper;
import com.limitactivitysystem.activity.mapper.SessionZoneMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.ticketorder.dto.AllocationMode;
import com.limitactivitysystem.ticketorder.dto.CreateTicketOrderItemRequest;
import com.limitactivitysystem.ticketorder.dto.CreateTicketOrderRequest;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderItemMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.mapper.UserSessionPurchaseMapper;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import com.limitactivitysystem.ticketorder.model.TicketOrderItem;
import com.limitactivitysystem.whitelist.service.QualificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TicketOrderCreationTimeTest {
    private static final LocalDateTime OPEN = LocalDateTime.of(2026, 10, 24, 12, 0);
    @Mock private CurrentUserProvider currentUser;
    @Mock private QualificationService qualification;
    @Mock private ActivityMapper activities;
    @Mock private ActivitySessionMapper sessions;
    @Mock private SessionZoneMapper zones;
    @Mock private SessionSeatMapper seats;
    @Mock private UserSessionPurchaseMapper purchases;
    @Mock private DatabaseTimeMapper time;
    @Mock private TicketOrderMapper orders;
    @Mock private TicketOrderItemMapper items;
    @InjectMocks private TicketOrderCreationService service;
    private TicketOrder stored;
    private List<TicketOrderItem> storedItems;

    @BeforeEach
    void currentPublishedScope() {
        Activity activity = new Activity();
        activity.setActivityId(10L);
        activity.setStatus("PUBLISHED");
        activity.setType("PAID");
        activity.setOpenTime(OPEN);
        activity.setCloseTime(OPEN.plusHours(1));
        ActivitySession session = new ActivitySession();
        session.setActivitySessionId(100L);
        session.setActivityId(10L);
        session.setStartTime(OPEN.plusHours(2));
        session.setMaxTicketsPerUser(4);
        session.setSeatMode("AUTO_ASSIGN");
        given(currentUser.getCurrentUserId()).willReturn(1L);
        given(sessions.findById(100L)).willReturn(session);
        given(activities.findById(10L)).willReturn(activity);
        given(activities.findByIdForShare(10L)).willReturn(activity);
        given(sessions.findByIdForShare(100L)).willReturn(session);
    }

    @ParameterizedTest
    @CsvSource({"-1,ORDER_WINDOW_NOT_OPEN", "0,OK", "1,OK", "3599999,OK", "3600000,ORDER_WINDOW_CLOSED",
            "3600001,ORDER_WINDOW_CLOSED", "7199999,ORDER_WINDOW_CLOSED", "7200000,SESSION_STARTED", "7200001,SESSION_STARTED"})
    void creationWindowIsInclusiveAtOpenAndExclusiveAtClose(long millisAfterOpen, String expected) {
        LocalDateTime databaseNow = OPEN.plusNanos(millisAfterOpen * 1_000_000);
        given(time.currentTime()).willReturn(databaseNow);
        if ("OK".equals(expected)) {
            prepareStock();
            given(orders.insertDuringCreationWindow(any(), any(), any(), any())).willAnswer(invocation -> {
                stored = invocation.getArgument(0);
                stored.setOrderId(50L);
                stored.setCreatedAt(databaseNow);
                stored.setExpiresAt(databaseNow.plusMinutes(15));
                return 1;
            });
            given(items.batchInsert(anyList())).willAnswer(invocation -> {
                storedItems = invocation.getArgument(0);
                storedItems.get(0).setOrderItemId(60L);
                return storedItems.size();
            });
            given(orders.findById(50L)).willAnswer(invocation -> stored);
            given(items.findByOrderId(50L)).willAnswer(invocation -> storedItems);
            assertThat(service.createOrder(request()).orderId()).isEqualTo(50L);
        } else {
            assertDenied(expected);
            verifyNoInteractions(orders, items, zones, seats, purchases);
        }
    }

    @Test
    void validationThatCrossesTheCloseTimeCannotWriteAnOrder() {
        prepareStock();
        given(time.currentTime()).willReturn(OPEN, OPEN.plusHours(1));
        assertDenied("ORDER_WINDOW_CLOSED");
        verifyNoInteractions(orders, items);
    }

    @Test
    void theFinalDatabaseInsertGuardRejectsCrossingTheDeadline() {
        prepareStock();
        given(time.currentTime()).willReturn(OPEN, OPEN, OPEN.plusHours(1));
        given(orders.insertDuringCreationWindow(any(), any(), any(), any())).willReturn(0);
        assertDenied("ORDER_WINDOW_CLOSED");
        verifyNoInteractions(items);
    }

    private void assertDenied(String code) {
        assertThatThrownBy(() -> service.createOrder(request())).isInstanceOfSatisfying(TicketOrderException.class,
                exception -> assertThat(exception.getErrorCode().name()).isEqualTo(code));
    }

    private void prepareStock() {
        SessionZone zone = new SessionZone();
        zone.setZoneId(30L);
        zone.setSessionId(100L);
        zone.setZoneType("PAID");
        zone.setPrice(new BigDecimal("0.10"));
        zone.setRemainsAmount(2);
        given(zones.findById(30L)).willReturn(zone);
    }

    private CreateTicketOrderRequest request() {
        CreateTicketOrderItemRequest item = new CreateTicketOrderItemRequest();
        item.setZoneId(30L);
        item.setAllocationMode(AllocationMode.AUTO_ASSIGN);
        item.setQuantity(1);
        item.setAcceptNonAdjacent(true);
        CreateTicketOrderRequest request = new CreateTicketOrderRequest();
        request.setRequestId("creation-time");
        request.setSessionId(100L);
        request.setItems(List.of(item));
        return request;
    }
}
