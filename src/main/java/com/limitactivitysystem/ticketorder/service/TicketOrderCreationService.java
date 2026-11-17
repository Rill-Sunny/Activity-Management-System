package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.mapper.SessionSeatMapper;
import com.limitactivitysystem.activity.mapper.SessionZoneMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionSeat;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.ticketorder.dto.AllocationMode;
import com.limitactivitysystem.ticketorder.dto.CreateTicketOrderItemRequest;
import com.limitactivitysystem.ticketorder.dto.CreateTicketOrderRequest;
import com.limitactivitysystem.ticketorder.dto.TicketOrderResponse;
import com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderItemMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.mapper.UserSessionPurchaseMapper;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import com.limitactivitysystem.ticketorder.model.TicketOrderItem;
import com.limitactivitysystem.outbox.OutboxEventWriter;
import com.limitactivitysystem.whitelist.service.QualificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode.*;

@Service
@Validated
public class TicketOrderCreationService {
    private static final BigDecimal MAX_ORDER_AMOUNT = new BigDecimal("9999999999.99");
    private final CurrentUserProvider currentUser;
    private final QualificationService qualification;
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final SessionZoneMapper zones;
    private final SessionSeatMapper seats;
    private final UserSessionPurchaseMapper purchases;
    private final DatabaseTimeMapper time;
    private final TicketOrderMapper orders;
    private final TicketOrderItemMapper orderItems;
    private final OutboxEventWriter outbox;

    @Autowired
    public TicketOrderCreationService(CurrentUserProvider currentUser, QualificationService qualification,
                              ActivityMapper activities, ActivitySessionMapper sessions, SessionZoneMapper zones,
                              SessionSeatMapper seats, UserSessionPurchaseMapper purchases, DatabaseTimeMapper time,
                              TicketOrderMapper orders, TicketOrderItemMapper orderItems, OutboxEventWriter outbox) {
        this.currentUser = currentUser;
        this.qualification = qualification;
        this.activities = activities;
        this.sessions = sessions;
        this.zones = zones;
        this.seats = seats;
        this.purchases = purchases;
        this.time = time;
        this.orders = orders;
        this.orderItems = orderItems;
        this.outbox = outbox;
    }

    /** Keeps the former constructor usable by isolated unit fixtures. The application always injects OutboxEventWriter. */
    public TicketOrderCreationService(CurrentUserProvider currentUser, QualificationService qualification,
                              ActivityMapper activities, ActivitySessionMapper sessions, SessionZoneMapper zones,
                              SessionSeatMapper seats, UserSessionPurchaseMapper purchases, DatabaseTimeMapper time,
                              TicketOrderMapper orders, TicketOrderItemMapper orderItems) {
        this(currentUser, qualification, activities, sessions, zones, seats, purchases, time, orders, orderItems, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public TicketOrderResponse createOrder(@NotNull @Valid CreateTicketOrderRequest request) {
        Long buyerId = currentUser.getCurrentUserId();
        ActivitySession session = requireSession(sessions.findById(request.getSessionId()));
        Activity activity = requireActivity(activities.findById(session.getActivityId()));
        requireTradableLifecycle(activity, session);

        // Joins this transaction. Shared account/root/scope locks coordinate with whitelist changes.
        qualification.check(activity.getActivityId(), session.getActivitySessionId());
        activity = requireActivity(activities.findByIdForShare(activity.getActivityId()));
        session = requireSession(sessions.findByIdForShare(session.getActivitySessionId()));
        if (!activity.getActivityId().equals(session.getActivityId())) {
            throw failure(INVALID_SESSION_CONFIG, "Session does not belong to the activity");
        }
        requireTradableLifecycle(activity, session);
        requireCreationTime(activity, session, time.currentTime());

        Map<Long, SessionZone> checkedZones = new HashMap<>();
        Map<Long, Long> requestedByZone = new HashMap<>();
        Set<Long> selectedSeats = new HashSet<>();
        List<TicketOrderItem> prepared = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        long requestedTotal = 0;

        for (CreateTicketOrderItemRequest input : request.getItems()) {
            SessionZone zone = checkedZones.get(input.getZoneId());
            if (zone == null) {
                zone = zones.findById(input.getZoneId());
                if (zone == null) { throw failure(ZONE_NOT_FOUND, "Zone not found"); }
                if (!request.getSessionId().equals(zone.getSessionId())) {
                    throw failure(INVALID_ZONE_FOR_SESSION, "Zone does not belong to the requested session");
                }
                if (!"PAID".equals(zone.getZoneType())) {
                    throw failure(GIFT_ZONE_NOT_PURCHASABLE, "Ticket orders only accept PAID zones");
                }
                if (zone.getPrice() == null || zone.getPrice().signum() <= 0) {
                    throw failure(INVALID_ZONE_PRICE, "Zone must have a positive price");
                }
                checkedZones.put(zone.getZoneId(), zone);
            }
            validateItem(input, session, selectedSeats);

            TicketOrderItem item = new TicketOrderItem();
            item.setZoneId(zone.getZoneId());
            item.setAllocationMode(input.getAllocationMode().name());
            item.setRequestedSeatId(input.getRequestedSeatId());
            item.setQuantity(input.getQuantity());
            item.setAcceptNonAdjacent(input.getAcceptNonAdjacent());
            item.setUnitPrice(zone.getPrice());
            prepared.add(item);
            total = total.add(zone.getPrice().multiply(BigDecimal.valueOf(input.getQuantity())));
            requestedTotal += input.getQuantity().longValue();
            requestedByZone.merge(zone.getZoneId(), input.getQuantity().longValue(), Long::sum);
        }

        if (total.compareTo(MAX_ORDER_AMOUNT) > 0) {
            throw failure(ORDER_AMOUNT_EXCEEDED, "Order amount exceeds the supported range");
        }
        for (Map.Entry<Long, Long> entry : requestedByZone.entrySet()) {
            Integer remains = checkedZones.get(entry.getKey()).getRemainsAmount();
            if (remains == null || entry.getValue() > remains.longValue()) {
                throw failure(INSUFFICIENT_CURRENT_INVENTORY, "Current zone inventory is insufficient");
            }
        }
        // Advisory reads only: pending orders do not reserve inventory or consume purchase quota.
        if ((long) purchases.findValidCount(buyerId, session.getActivitySessionId()) + requestedTotal
                > session.getMaxTicketsPerUser()) {
            throw failure(PURCHASE_LIMIT_PRECHECK_FAILED, "Current purchase limit would be exceeded");
        }

        TicketOrder order = new TicketOrder();
        order.setBuyerId(buyerId);
        order.setSessionId(session.getActivitySessionId());
        order.setRequestId(request.getRequestId());
        order.setTotalAmount(total);
        requireCreationTime(activity, session, time.currentTime());
        if (orders.insertDuringCreationWindow(order, activity.getOpenTime(), activity.getCloseTime(),
                session.getStartTime()) != 1) {
            requireCreationTime(activity, session, time.currentTime());
            throw failure(ORDER_CREATION_CONFLICT, "Order could not be created in the current window");
        }
        for (TicketOrderItem item : prepared) { item.setOrderId(order.getOrderId()); }
        if (orderItems.batchInsert(prepared) != prepared.size()) {
            throw failure(ORDER_CREATION_CONFLICT, "Order items could not all be saved");
        }
        TicketOrder stored = orders.findById(order.getOrderId());
        if (outbox != null) {
            outbox.append("ORDER_EXPIRED", order.getOrderId(), Map.of(
                    "orderId", order.getOrderId(),
                    "buyerId", buyerId,
                    "expiresAt", stored.getExpiresAt()));
        }
        return TicketOrderResponses.from(stored, orderItems.findByOrderId(order.getOrderId()));
    }

    private void validateItem(CreateTicketOrderItemRequest item, ActivitySession session, Set<Long> selectedSeats) {
        if (item.getAllocationMode() == null || !item.getAllocationMode().name().equals(session.getSeatMode())) {
            throw failure(INVALID_ALLOCATION_MODE, "Allocation mode must match the session");
        }
        if (item.getQuantity() == null || item.getQuantity() <= 0 || item.getAcceptNonAdjacent() == null) {
            throw failure(INVALID_ORDER_ITEM, "Quantity and non-adjacent preference are required");
        }
        if (item.getAllocationMode() == AllocationMode.SELECT_SEAT) {
            if (item.getRequestedSeatId() == null || item.getQuantity() != 1 || item.getAcceptNonAdjacent()) {
                throw failure(INVALID_ORDER_ITEM, "Selected seating requires one seat and no non-adjacent option");
            }
            SessionSeat seat = seats.findById(item.getRequestedSeatId());
            if (seat == null) { throw failure(SEAT_NOT_FOUND, "Seat not found"); }
            if (!item.getZoneId().equals(seat.getZoneId())) {
                throw failure(INVALID_SEAT_FOR_ZONE, "Seat does not belong to the requested zone");
            }
            if (!selectedSeats.add(seat.getSeatId())) {
                throw failure(DUPLICATE_REQUESTED_SEAT, "The same seat cannot appear twice in an order");
            }
            // Do not check AVAILABLE or change the seat state here. This is purchase intent only.
        } else if (item.getRequestedSeatId() != null) {
            throw failure(INVALID_ORDER_ITEM, "Automatic assignment cannot specify a seat");
        }
    }

    private void requireTradableLifecycle(Activity activity, ActivitySession session) {
        if (!"PUBLISHED".equals(activity.getStatus())) {
            throw failure(ACTIVITY_NOT_PUBLISHED, "Activity must be published before creating ticket orders");
        }
        if (!"PAID".equals(activity.getType())) {
            throw failure(ACTIVITY_NOT_PAID, "Only PAID activities accept ticket orders");
        }
        if (!"SCHEDULED".equals(session.getStatus())) {
            throw failure(SESSION_NOT_AVAILABLE, "Session is not accepting ticket orders");
        }
        if (session.getMaxTicketsPerUser() == null || session.getMaxTicketsPerUser() <= 0
                || (!"SELECT_SEAT".equals(session.getSeatMode()) && !"AUTO_ASSIGN".equals(session.getSeatMode()))) {
            throw failure(INVALID_SESSION_CONFIG, "Paid session configuration is invalid");
        }
    }

    /** Creation-only rule. Paying an existing order must use its own expiry rules instead. */
    private void requireCreationTime(Activity activity, ActivitySession session, LocalDateTime now) {
        if (!now.isBefore(session.getStartTime())) {
            throw failure(SESSION_STARTED, "Session has already started");
        }
        if (now.isBefore(activity.getOpenTime())) {
            throw failure(ORDER_WINDOW_NOT_OPEN, "Ticket order creation has not opened");
        }
        if (!now.isBefore(activity.getCloseTime())) {
            throw failure(ORDER_WINDOW_CLOSED, "Ticket order creation has closed");
        }
    }

    private ActivitySession requireSession(ActivitySession session) {
        if (session == null) { throw failure(SESSION_NOT_FOUND, "Session not found"); }
        return session;
    }

    private Activity requireActivity(Activity activity) {
        if (activity == null) { throw failure(ACTIVITY_NOT_FOUND, "Activity not found"); }
        return activity;
    }

    private TicketOrderException failure(TicketOrderErrorCode code, String message) {
        return new TicketOrderException(code, message);
    }
}
