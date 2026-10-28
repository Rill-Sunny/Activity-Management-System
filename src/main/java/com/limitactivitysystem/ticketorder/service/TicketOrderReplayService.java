package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.ticketorder.dto.CreateTicketOrderRequest;
import com.limitactivitysystem.ticketorder.dto.TicketOrderResponse;
import com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderItemMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketOrderReplayService {
    private final CurrentUserProvider currentUser;
    private final TicketOrderMapper orders;
    private final TicketOrderItemMapper items;

    public TicketOrderReplayService(CurrentUserProvider currentUser, TicketOrderMapper orders,
                                   TicketOrderItemMapper items) {
        this.currentUser = currentUser;
        this.orders = orders;
        this.items = items;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public TicketOrderResponse findIfPresent(CreateTicketOrderRequest request) {
        Long buyerId = currentUser.getCurrentUserId();
        var order = orders.findByBuyerIdAndRequestId(buyerId, request.getRequestId());
        if (order == null) { return null; }
        var storedItems = items.findByOrderId(order.getOrderId());
        if (!TicketOrderIntent.matches(order, storedItems, request)) {
            throw new TicketOrderException(TicketOrderErrorCode.IDEMPOTENCY_CONFLICT,
                    "Request ID has already been used with a different purchase intent");
        }
        return TicketOrderResponses.from(order, storedItems);
    }
}
