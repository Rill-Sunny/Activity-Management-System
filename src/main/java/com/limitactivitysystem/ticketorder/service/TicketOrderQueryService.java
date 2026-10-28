package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.ticketorder.dto.TicketOrderResponse;
import com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderItemMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class TicketOrderQueryService {
    private final CurrentUserProvider currentUser;
    private final TicketOrderMapper orders;
    private final TicketOrderItemMapper items;

    public TicketOrderQueryService(CurrentUserProvider currentUser, TicketOrderMapper orders,
                                   TicketOrderItemMapper items) {
        this.currentUser = currentUser;
        this.orders = orders;
        this.items = items;
    }

    @Transactional(readOnly = true)
    public TicketOrderResponse getOwnOrder(@NotNull @Positive Long orderId) {
        Long userId = currentUser.getCurrentUserId();
        var order = orders.findById(orderId);
        if (order == null) {
            throw new TicketOrderException(TicketOrderErrorCode.ORDER_NOT_FOUND, "Ticket order not found");
        }
        if (!userId.equals(order.getBuyerId())) {
            throw new TicketOrderException(TicketOrderErrorCode.ORDER_ACCESS_DENIED,
                    "Only the buyer can access this ticket order");
        }
        return TicketOrderResponses.from(order, items.findByOrderId(orderId));
    }
}
