package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.ticketorder.dto.TicketPaymentResponse;
import com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderItemMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import com.limitactivitysystem.ticketorder.model.TicketOrderItem;
import com.limitactivitysystem.whitelist.service.QualificationService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.List;

import static com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode.*;

@Service
@Validated
public class TicketPaymentTransactionService {
    private final TicketOrderMapper orders;
    private final TicketOrderItemMapper items;
    private final CurrentUserProvider currentUser;
    private final AppUserMapper users;
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final QualificationService qualification;
    private final DatabaseTimeMapper databaseTime;
    private final TicketPaymentResourceService resources;
    private final SeatAllocator seats;
    private final TicketIssuanceService issuance;

    public TicketPaymentTransactionService(TicketOrderMapper orders, TicketOrderItemMapper items, CurrentUserProvider currentUser,
                                AppUserMapper users, ActivityMapper activities, ActivitySessionMapper sessions,
                                QualificationService qualification, DatabaseTimeMapper databaseTime,
                                TicketPaymentResourceService resources, SeatAllocator seats, TicketIssuanceService issuance) {
        this.orders = orders; this.items = items; this.currentUser = currentUser; this.users = users;
        this.activities = activities; this.sessions = sessions; this.qualification = qualification;
        this.databaseTime = databaseTime; this.resources = resources; this.seats = seats;
        this.issuance = issuance;
    }

    /** Conflict recovery runs only after this transaction proxy has finished rollback. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public TicketPaymentResponse pay(@NotNull @Positive Long orderId) {
        TicketOrder order = lockAndValidateOrder(orderId);
        Long userId = validateCurrentUser(order);
        if ("PAID".equals(order.getStatus())) { return issuance.response(order); }
        validatePendingOrder(order, databaseTime.currentTime());
        Scope scope = validateCurrentQualification(order, userId);
        List<TicketOrderItem> savedItems = loadOrderItems(order);
        int quantity = totalQuantity(savedItems);

        // Waiting on eligibility/other locks may cross the order deadline or session start.
        validatePendingOrder(order, databaseTime.currentTime());
        resources.occupyPurchaseQuota(userId, order.getSessionId(), quantity, scope.session().getMaxTicketsPerUser());
        // The coordinator performs decreaseZoneStock -> allocateSeats exactly once, under the same Zone locks.
        var allocated = seats.allocate(order.getSessionId(), savedItems);
        if (allocated.size() != quantity) { throw new IllegalStateException("Allocated seat count does not match order quantity"); }
        LocalDateTime now = databaseTime.currentTime();
        validatePendingOrder(order, now);
        validateTradableScope(scope.activity(), scope.session(), now);

        issuance.createTickets(order, savedItems, allocated);
        markOrderPaid(order, scope);
        return issuance.response(orders.findById(orderId));
    }

    private void markOrderPaid(TicketOrder order, Scope scope) {
        if (orders.markPaidIfPayable(order.getOrderId()) != 1) {
            LocalDateTime now = databaseTime.currentTime();
            validatePendingOrder(order, now);
            validateTradableScope(scope.activity(), scope.session(), now);
            throw failure(ORDER_PAYMENT_CONFLICT, "Order could not transition to paid");
        }
        // CURRENT_TIMESTAMP is statement-start time. Reject a statement that waited across a deadline too.
        LocalDateTime afterUpdate = databaseTime.currentTime();
        validatePendingOrder(order, afterUpdate);
        validateTradableScope(scope.activity(), scope.session(), afterUpdate);
    }

    private TicketOrder lockAndValidateOrder(Long orderId) {
        TicketOrder order = orders.findByIdForUpdate(orderId);
        if (order == null) { throw failure(ORDER_NOT_FOUND, "Ticket order not found"); }
        return order;
    }

    private Long validateCurrentUser(TicketOrder order) {
        Long userId = currentUser.getAuthenticatedUserId();
        if (!userId.equals(order.getBuyerId())) { throw failure(ORDER_ACCESS_DENIED, "Only the buyer can pay this order"); }
        var user = users.findByIdForShare(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw failure(USER_DISABLED, "Current account is not active");
        }
        return userId;
    }

    private void validatePendingOrder(TicketOrder order, LocalDateTime now) {
        if (!"PENDING_PAYMENT".equals(order.getStatus())) { throw failure(ORDER_NOT_PENDING, "Order is not pending payment"); }
        if (order.getExpiresAt() == null || !now.isBefore(order.getExpiresAt())) {
            throw failure(ORDER_EXPIRED, "Order payment deadline has passed");
        }
        if (order.getCreatedAt() != null && now.isBefore(order.getCreatedAt())) {
            throw failure(ORDER_NOT_PENDING, "Order creation time is in the future");
        }
    }

    private Scope validateCurrentQualification(TicketOrder order, Long userId) {
        ActivitySession session = sessions.findById(order.getSessionId());
        if (session == null) { throw failure(SESSION_NOT_AVAILABLE, "Order session is unavailable"); }
        Activity activity = activities.findById(session.getActivityId());
        if (activity == null) { throw failure(ACTIVITY_NOT_FOUND, "Order activity not found"); }
        validateTradableScope(activity, session, databaseTime.currentTime());
        if (!userId.equals(qualification.checkAgain(activity.getActivityId(), session.getActivitySessionId()))) {
            throw failure(ORDER_ACCESS_DENIED, "Qualification identity does not match the order buyer");
        }
        activity = activities.findByIdForShare(activity.getActivityId());
        session = sessions.findByIdForShare(order.getSessionId());
        if (activity == null || session == null || !activity.getActivityId().equals(session.getActivityId())) {
            throw failure(SESSION_NOT_AVAILABLE, "Order scope is unavailable");
        }
        validateTradableScope(activity, session, databaseTime.currentTime());
        return new Scope(activity, session);
    }

    private void validateTradableScope(Activity activity, ActivitySession session, LocalDateTime now) {
        if (!"PUBLISHED".equals(activity.getStatus())) { throw failure(ACTIVITY_NOT_PUBLISHED, "Activity is not tradable"); }
        if (!"PAID".equals(activity.getType())) { throw failure(ACTIVITY_NOT_PAID, "Activity is not paid"); }
        if (!"SCHEDULED".equals(session.getStatus()) || session.getStartTime() == null || !now.isBefore(session.getStartTime())) {
            throw failure(SESSION_NOT_AVAILABLE, "Session is cancelled, finished or already started");
        }
        if (session.getMaxTicketsPerUser() == null || session.getMaxTicketsPerUser() <= 0
                || (!"SELECT_SEAT".equals(session.getSeatMode()) && !"AUTO_ASSIGN".equals(session.getSeatMode()))) {
            throw failure(INVALID_SESSION_CONFIG, "Paid session configuration is invalid");
        }
        // The Activity open/close window governs creation only; it is deliberately not checked here.
    }

    private List<TicketOrderItem> loadOrderItems(TicketOrder order) {
        var saved = items.findByOrderId(order.getOrderId());
        if (saved.isEmpty() || saved.stream().anyMatch(item -> !order.getOrderId().equals(item.getOrderId()))) {
            throw failure(INVALID_ORDER_ITEM, "Order has no valid persisted items");
        }
        return saved;
    }

    private int totalQuantity(List<TicketOrderItem> saved) {
        int total = 0;
        for (TicketOrderItem item : saved) {
            if (item.getQuantity() == null || item.getQuantity() <= 0) { throw failure(INVALID_ORDER_ITEM, "Item quantity is invalid"); }
            try { total = Math.addExact(total, item.getQuantity()); }
            catch (ArithmeticException overflow) { throw failure(INVALID_ORDER_ITEM, "Order quantity is too large"); }
        }
        return total;
    }

    private TicketOrderException failure(TicketOrderErrorCode code, String message) { return new TicketOrderException(code, message); }
    private record Scope(Activity activity, ActivitySession session) { }
}
