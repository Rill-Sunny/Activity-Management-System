package com.limitactivitysystem.refund.service;

import com.limitactivitysystem.activity.mapper.SessionSeatMapper;
import com.limitactivitysystem.activity.mapper.SessionZoneMapper;
import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.refund.dto.TicketRefundItemResponse;
import com.limitactivitysystem.refund.dto.TicketRefundResponse;
import com.limitactivitysystem.refund.exception.TicketRefundException;
import com.limitactivitysystem.refund.mapper.RefundTicketMapper;
import com.limitactivitysystem.refund.mapper.TicketRefundMapper;
import com.limitactivitysystem.refund.model.RefundTicketResource;
import com.limitactivitysystem.refund.model.TicketRefundRequest;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.qualification.service.QualificationGuardService;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.mapper.UserSessionPurchaseMapper;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import com.limitactivitysystem.outbox.OutboxEventWriter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;

import static com.limitactivitysystem.refund.exception.TicketRefundErrorCode.*;

@Service
@Validated
public class TicketRefundProcessingService {
    private final CurrentUserProvider currentUser;
    private final AppUserMapper users;
    private final TicketOrderMapper orders;
    private final TicketRefundMapper refunds;
    private final RefundTicketMapper tickets;
    private final UserSessionPurchaseMapper purchases;
    private final SessionZoneMapper zones;
    private final SessionSeatMapper seats;
    private final DatabaseTimeMapper databaseTime;
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final QualificationGuardService qualificationGuards;
    private final OutboxEventWriter outbox;

    @Autowired
    public TicketRefundProcessingService(CurrentUserProvider currentUser, AppUserMapper users,
            TicketOrderMapper orders, TicketRefundMapper refunds, RefundTicketMapper tickets,
            UserSessionPurchaseMapper purchases, SessionZoneMapper zones, SessionSeatMapper seats,
            DatabaseTimeMapper databaseTime, ActivityMapper activities, ActivitySessionMapper sessions,
            QualificationGuardService qualificationGuards, OutboxEventWriter outbox) {
        this.currentUser = currentUser; this.users = users; this.orders = orders; this.refunds = refunds;
        this.tickets = tickets; this.purchases = purchases; this.zones = zones; this.seats = seats;
        this.databaseTime = databaseTime;
        this.activities = activities; this.sessions = sessions; this.qualificationGuards = qualificationGuards;
        this.outbox = outbox;
    }

    public TicketRefundProcessingService(CurrentUserProvider currentUser, AppUserMapper users,
            TicketOrderMapper orders, TicketRefundMapper refunds, RefundTicketMapper tickets,
            UserSessionPurchaseMapper purchases, SessionZoneMapper zones, SessionSeatMapper seats,
            DatabaseTimeMapper databaseTime, ActivityMapper activities, ActivitySessionMapper sessions,
            QualificationGuardService qualificationGuards) {
        this(currentUser, users, orders, refunds, tickets, purchases, zones, seats, databaseTime,
                activities, sessions, qualificationGuards, null);
    }

    /** Executes an accepted quote atomically. No policy, qualification, or session-time reevaluation. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public TicketRefundResponse process(@NotNull @Positive Long refundId) {
        Long buyerId = currentUser.getAuthenticatedUserId();
        return execute(refundId,buyerId,false);
    }

    /** Internal cancellation worker only; joins its effect + task transaction, never an HTTP actor override. */
    @Transactional(propagation=Propagation.MANDATORY)
    public TicketRefundResponse processForCancellation(@NotNull @Positive Long refundId,@NotNull @Positive Long eventId) {
        var request=refunds.findById(refundId);
        if(request==null || !"CANCELLATION_REFUND".equals(request.getRefundPolicy())
                || !eventId.equals(request.getSourceEventId()) || refunds.cancellationScope(eventId,request.getOrderId())!=1) {
            throw new TicketRefundException(REFUND_ACCESS_DENIED,"Cancellation refund scope is invalid");
        }
        return execute(refundId,request.getBuyerId(),true);
    }

    private TicketRefundResponse execute(Long refundId,Long buyerId,boolean cancellation) {
        var discovered = refunds.findById(refundId);
        if (discovered == null) { throw new TicketRefundException(REFUND_NOT_FOUND, "Refund request not found"); }
        com.limitactivitysystem.observability.BusinessTelemetry.refundPolicy(discovered.getRefundPolicy());
        requireBuyer(buyerId, discovered.getBuyerId());
        // Discovery does not lock the request. All payment/refund writers acquire Order first.
        var order = orders.findByIdForUpdate(discovered.getOrderId());
        if (order == null) { throw new TicketRefundException(REFUND_ORDER_NOT_FOUND, "Ticket order not found"); }
        requireBuyer(buyerId, order.getBuyerId());
        var request = refunds.findByIdForUpdate(refundId);
        if (request == null) { throw new TicketRefundException(REFUND_NOT_FOUND, "Refund request not found"); }
        requireBuyer(buyerId, request.getBuyerId());
        require(order.getOrderId().equals(request.getOrderId()), "Refund order reference changed");
        var user = users.findByIdForShare(buyerId);
        if (user == null || (!cancellation && !"ACTIVE".equals(user.getStatus()))) {
            throw new TicketRefundException(REFUND_USER_DISABLED, "Current account is not active");
        }
        if ("SUCCEEDED".equals(request.getStatus())) { return response(request); }
        boolean retry = "FAILED".equals(request.getStatus()) && "TECHNICAL_FAILURE".equals(request.getResult());
        if (!"PENDING".equals(request.getStatus()) && !retry) {
            throw new TicketRefundException(REFUND_NOT_PENDING, "Only pending refunds can be processed");
        }
        if (!List.of("PAID", "PARTIALLY_REFUNDED").contains(order.getStatus())) {
            throw new TicketRefundException(REFUND_ORDER_NOT_PAID, "Refund order is not paid");
        }
        var items = refunds.findItemsForUpdate(refundId);
        require(!items.isEmpty(), "Accepted refund has no items");
        if (retry) {
            com.limitactivitysystem.observability.BusinessTelemetry.refundRetry();
            // Reclaim the same tickets under the unique claim key before taking shared resources.
            for (var item : items) { changed(refunds.reopenItem(item.refundItemId()), "Failed refund item cannot be retried"); }
            changed(refunds.reopen(refundId), "Failed refund cannot be retried");
            items = refunds.findItemsForUpdate(refundId);
        }
        var selected = discoverResources(order, items);
        var discoveredSession = sessions.findById(order.getSessionId());
        if (discoveredSession == null) { throw new TicketRefundException(REFUND_RESOURCE_CONFLICT, "Refund session is missing"); }
        var activity = activities.findByIdForShare(discoveredSession.getActivityId());
        var session = sessions.findByIdForShare(order.getSessionId());
        if (activity == null || session == null || !activity.getActivityId().equals(discoveredSession.getActivityId())
                || !activity.getActivityId().equals(session.getActivityId())) {
            throw new TicketRefundException(REFUND_RESOURCE_CONFLICT, "Refund activity or session is inconsistent");
        }
        if(!cancellation && "CANCELLED".equals(session.getStatus())) {
            throw new TicketRefundException(REFUND_RESOURCE_CONFLICT,"Cancelled sessions are refunded by their full-refund batch");
        }
        qualificationGuards.lockActivities(buyerId, List.of(activity.getActivityId()));
        lockAndValidateResources(order, items, selected);

        // All resources are locked and validated before resource restoration starts.
        var processedAt = databaseTime.currentTime();
        changed(refunds.markProcessing(refundId), "Refund could not enter processing");
        for (var item : items) { changed(refunds.markItemProcessing(item.refundItemId()), "Refund item could not enter processing"); }
        changed(purchases.tryDecrease(buyerId, order.getSessionId(), items.size()), "Purchase quota could not be restored");
        var quantities = new TreeMap<Long, Integer>();
        for (var resource : selected) { quantities.merge(resource.zoneId(), 1, Integer::sum); }
        quantities.forEach((zoneId, quantity) -> changed(zones.restoreStock(zoneId, order.getSessionId(), quantity), "Zone stock could not be restored"));
        var bySeat = new TreeMap<Long, RefundTicketResource>();
        for (var resource : selected) { bySeat.put(resource.seatId(), resource); }
        bySeat.forEach((seatId, resource) -> changed(seats.releaseSold(seatId, resource.zoneId()), "Sold seat could not be released"));
        for (var item : items) {
            changed(tickets.markRefunded(item.ticketId(), processedAt), "Ticket could not be refunded");
            changed(refunds.markItemSucceeded(item.refundItemId(), processedAt), "Refund item could not succeed");
        }
        long qualificationVersion = qualificationGuards.advanceVersion(buyerId, activity.getActivityId());
        long remaining = tickets.countValidByOrder(order.getOrderId());
        changed(orders.updateRefundSummary(order.getOrderId(), remaining == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED"), "Order summary could not be updated");
        changed(refunds.markSucceeded(refundId, processedAt), "Refund request could not succeed");
        if (outbox != null) {
            outbox.append("REFUND_SUCCEEDED", refundId, Map.of(
                    "refundId", refundId,
                    "orderId", order.getOrderId(),
                    "userId", buyerId,
                    "content", "退款申请 " + refundId + " 已成功处理"));
            outbox.append("TICKET_QUALIFICATION_CHANGED", refundId, Map.of(
                    "buyerId", buyerId,
                    "activityId", activity.getActivityId(),
                    "sessionId", order.getSessionId(),
                    "refundId", refundId,
                    "occurredAt", processedAt,
                    "eventVersion", qualificationVersion));
        }
        return response(refunds.findById(refundId));
    }

    private List<RefundTicketResource> discoverResources(TicketOrder order, List<TicketRefundItemResponse> items) {
        var selected = new ArrayList<RefundTicketResource>();
        for (var item : items) {
            require("PENDING".equals(item.status()), "Accepted refund item is not pending");
            var resource = tickets.findResource(item.ticketId());
            if (resource == null) { throw new TicketRefundException(REFUND_TICKET_NOT_FOUND, "Ticket or order item is missing"); }
            if (!order.getOrderId().equals(resource.orderId()) || !order.getBuyerId().equals(resource.buyerId())
                    || !order.getSessionId().equals(resource.sessionId())) {
                throw new TicketRefundException(REFUND_TICKET_NOT_IN_ORDER, "Ticket does not belong to the refund order");
            }
            selected.add(resource);
        }
        return selected;
    }

    private void lockAndValidateResources(TicketOrder order, List<TicketRefundItemResponse> items, List<RefundTicketResource> selected) {
        Integer count = purchases.findForUpdate(order.getBuyerId(), order.getSessionId());
        require(count != null && count >= items.size(), "Purchase quota is missing or insufficient");
        var quantities = new TreeMap<Long, Integer>();
        var bySeat = new TreeMap<Long, RefundTicketResource>();
        for (var resource : selected) {
            quantities.merge(resource.zoneId(), 1, Integer::sum);
            require(bySeat.put(resource.seatId(), resource) == null, "Refund tickets refer to the same seat");
        }
        quantities.forEach((id, quantity) -> {
            var zone = zones.findByIdForUpdate(id);
            require(zone != null && order.getSessionId().equals(zone.getSessionId()) && "PAID".equals(zone.getZoneType()), "Invalid refund zone relation");
            require(zone.getRemainsAmount() <= zone.getTotalAmount() - quantity, "Refund would exceed zone capacity");
        });
        bySeat.forEach((id, resource) -> {
            var seat = seats.findByIdForUpdate(id);
            require(seat != null && resource.zoneId().equals(seat.getZoneId()) && "SOLD".equals(seat.getAllocationStatus()), "Refund seat is not sold in the expected zone");
        });
        // Items are loaded in ticket_id order. Recheck discovery after acquiring each Ticket lock.
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var ticket = tickets.lockTicket(item.ticketId());
            if (ticket == null) { throw new TicketRefundException(REFUND_TICKET_NOT_FOUND, "Ticket is missing"); }
            if (!"VALID".equals(ticket.getStatus())) { throw new TicketRefundException(REFUND_TICKET_NOT_VALID, "Ticket is no longer valid"); }
            require(selected.get(i).equals(tickets.findResource(item.ticketId())), "Ticket resource references changed");
            require(selected.get(i).orderItemId().equals(ticket.getOrderItemId()) && selected.get(i).seatId().equals(ticket.getSeatId()), "Locked ticket differs from discovery");
            require(item.originalPrice().compareTo(ticket.getPaidPrice()) == 0, "Ticket price differs from the accepted quote");
        }
    }

    private TicketRefundResponse response(TicketRefundRequest request) {
        return new TicketRefundResponse(request.getRefundId(), request.getBuyerId(), request.getOrderId(),
                request.getRequestId(), request.getReason(), request.getRequestedAt(), request.getStatus(),
                request.getCreatedAt(), request.getProcessedAt(), request.getResult(), refunds.findItems(request.getRefundId()));
    }
    private void requireBuyer(Long actual, Long expected) {
        if (!actual.equals(expected)) { throw new TicketRefundException(REFUND_ACCESS_DENIED, "Only the buyer can process this refund"); }
    }
    private void require(boolean valid, String message) {
        if (!valid) { throw new TicketRefundException(REFUND_RESOURCE_CONFLICT, message); }
    }
    private void changed(int rows, String message) { require(rows == 1, message); }
}
