package com.limitactivitysystem.refund.service;

import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.refund.dto.*;
import com.limitactivitysystem.refund.exception.TicketRefundException;
import com.limitactivitysystem.refund.mapper.RefundTicketMapper;
import com.limitactivitysystem.refund.mapper.TicketRefundMapper;
import com.limitactivitysystem.refund.model.TicketRefundRequest;
import com.limitactivitysystem.refund.model.TicketRefundDecision;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.model.Ticket;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import static com.limitactivitysystem.refund.exception.TicketRefundErrorCode.*;

@Service
public class TicketRefundApplicationService {
    private final TicketOrderMapper orders;
    private final AppUserMapper users;
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final RefundTicketMapper tickets;
    private final TicketRefundMapper refunds;
    private final TicketRefundPolicy policy;

    public TicketRefundApplicationService(TicketOrderMapper orders, AppUserMapper users, ActivityMapper activities,
            ActivitySessionMapper sessions, RefundTicketMapper tickets, TicketRefundMapper refunds,
            TicketRefundPolicy policy) {
        this.orders = orders; this.users = users; this.activities = activities; this.sessions = sessions;
        this.tickets = tickets; this.refunds = refunds; this.policy = policy;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public TicketRefundResponse apply(Long buyerId, CreateTicketRefundRequest input, LocalDateTime requestedAt) {
        var order = orders.findByIdForUpdate(input.getOrderId());
        if (order == null) { throw new TicketRefundException(REFUND_ORDER_NOT_FOUND, "Ticket order not found"); }
        if (!buyerId.equals(order.getBuyerId())) { throw new TicketRefundException(REFUND_ACCESS_DENIED, "Only the buyer can request a refund"); }
        var user = users.findByIdForShare(buyerId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) { throw new TicketRefundException(REFUND_USER_DISABLED, "Current account is not active"); }
        var existing = refunds.findByRequest(buyerId, input.getRequestId());
        if (existing != null) { return replay(existing, input); }
        if (!List.of("PAID", "PARTIALLY_REFUNDED").contains(order.getStatus())) {
            throw new TicketRefundException(REFUND_ORDER_NOT_PAID, "Only paid orders accept refund applications");
        }
        var preliminary = sessions.findById(order.getSessionId());
        if (preliminary == null) { throw new TicketRefundException(REFUND_SESSION_UNAVAILABLE, "Order session is missing"); }
        var activity = activities.findByIdForShare(preliminary.getActivityId());
        var session = sessions.findByIdForShare(order.getSessionId());
        if (activity == null || session == null || !activity.getActivityId().equals(session.getActivityId())
                || !"PAID".equals(activity.getType()) || !"PUBLISHED".equals(activity.getStatus())
                || !"SCHEDULED".equals(session.getStatus())) {
            throw new TicketRefundException(REFUND_SESSION_UNAVAILABLE, "Ordinary refund scope is unavailable");
        }
        var ids = input.getTicketIds().stream().sorted().toList();
        List<Ticket> selected = new ArrayList<>();
        for (Long id : ids) {
            requireOwnership(id, input.getOrderId(), buyerId);
            var ticket = tickets.lockTicket(id);
            if (ticket == null) { throw new TicketRefundException(REFUND_TICKET_NOT_FOUND, "Ticket not found"); }
            requireOwnership(id, input.getOrderId(), buyerId);
            if (!"VALID".equals(ticket.getStatus())) {
                throw new TicketRefundException(REFUND_TICKET_NOT_VALID, "Ticket is not valid");
            }
            if (requestedAt.isBefore(ticket.getIssuedAt())) {
                throw new TicketRefundException(REFUND_NOT_ALLOWED, "Refund application precedes ticket issuance");
            }
            selected.add(ticket);
        }
        List<QuotedTicket> quoted = new ArrayList<>();
        for (var ticket : selected) {
            var decision = policy.evaluate(requestedAt, activity.getOpenTime(), session.getStartTime(), ticket.getPaidPrice());
            if (!decision.allowed()) {
                throw new TicketRefundException(decision.rejectionReason(), "Ordinary refund policy does not allow this application");
            }
            quoted.add(new QuotedTicket(ticket, decision));
        }
        var request = new TicketRefundRequest();
        request.setBuyerId(buyerId); request.setOrderId(order.getOrderId()); request.setRequestId(input.getRequestId());
        request.setReason(input.getReason()); request.setRequestedAt(requestedAt);
        request.setOpenTimeSnapshot(activity.getOpenTime()); request.setStartTimeSnapshot(session.getStartTime());
        if (refunds.insertRequest(request) != 1) { throw new IllegalStateException("Refund application could not be saved"); }
        for (var quote : quoted) {
            if (refunds.insertItem(request.getRefundId(), quote.ticket().getTicketId(), quote.ticket().getPaidPrice(),
                    quote.decision().feeRate(), quote.decision().refundAmount()) != 1) {
                throw new IllegalStateException("Refund item could not be saved");
            }
        }
        return response(refunds.findByRequest(buyerId, input.getRequestId()));
    }

    private void requireOwnership(Long ticketId, Long orderId, Long buyerId) {
        var ownership = tickets.findOwnership(ticketId);
        if (ownership == null) { throw new TicketRefundException(REFUND_TICKET_NOT_FOUND, "Ticket or its order reference is missing"); }
        if (!orderId.equals(ownership.orderId()) || !buyerId.equals(ownership.buyerId())) {
            throw new TicketRefundException(REFUND_TICKET_NOT_IN_ORDER, "Ticket does not belong to this buyer's order");
        }
    }

    private TicketRefundResponse replay(TicketRefundRequest saved, CreateTicketRefundRequest input) {
        var items = refunds.findItems(saved.getRefundId());
        if (!saved.getOrderId().equals(input.getOrderId()) || !saved.getReason().equals(input.getReason())
                || !items.stream().map(TicketRefundItemResponse::ticketId).toList().equals(input.getTicketIds().stream().sorted().toList())) {
            throw new TicketRefundException(REFUND_IDEMPOTENCY_CONFLICT, "Refund requestId is already used for different content");
        }
        return response(saved);
    }

    private TicketRefundResponse response(TicketRefundRequest saved) {
        return new TicketRefundResponse(saved.getRefundId(), saved.getBuyerId(), saved.getOrderId(),
                saved.getRequestId(), saved.getReason(), saved.getRequestedAt(), saved.getStatus(),
                saved.getCreatedAt(), refunds.findItems(saved.getRefundId()));
    }
    private record QuotedTicket(Ticket ticket, TicketRefundDecision decision) { }
}
