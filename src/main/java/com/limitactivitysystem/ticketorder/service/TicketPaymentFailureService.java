package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.ticketorder.dto.TicketPaymentResponse;
import com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketPaymentFailureService {
    private final TicketOrderMapper orders;
    private final CurrentUserProvider currentUser;
    private final TicketIssuanceService issuance;
    public TicketPaymentFailureService(TicketOrderMapper orders, CurrentUserProvider currentUser, TicketIssuanceService issuance) {
        this.orders = orders; this.currentUser = currentUser; this.issuance = issuance;
    }

    /** Only ORD-05 stock failure; the complete payment transaction has already rolled back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public TicketPaymentResponse closeForInsufficientInventory(Long orderId) {
        var order = orders.findByIdForUpdate(orderId);
        if (order == null) { throw new TicketOrderException(TicketOrderErrorCode.ORDER_NOT_FOUND, "Ticket order not found"); }
        if (!currentUser.getAuthenticatedUserId().equals(order.getBuyerId())) {
            throw new TicketOrderException(TicketOrderErrorCode.ORDER_ACCESS_DENIED, "Only the buyer can close this failed payment");
        }
        if ("PAID".equals(order.getStatus())) { return issuance.response(order); }
        if ("PENDING_PAYMENT".equals(order.getStatus()) && orders.closePendingForInsufficientInventory(orderId) != 1) {
            throw new IllegalStateException("Locked pending order could not be closed");
        }
        return null;
    }
}
