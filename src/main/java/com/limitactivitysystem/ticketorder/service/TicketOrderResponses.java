package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.ticketorder.dto.AllocationMode;
import com.limitactivitysystem.ticketorder.dto.TicketOrderItemResponse;
import com.limitactivitysystem.ticketorder.dto.TicketOrderResponse;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import com.limitactivitysystem.ticketorder.model.TicketOrderItem;

import java.util.List;

final class TicketOrderResponses {
    private TicketOrderResponses() { }

    static TicketOrderResponse from(TicketOrder order, List<TicketOrderItem> items) {
        return new TicketOrderResponse(order.getOrderId(), order.getRequestId(), order.getSessionId(),
                order.getTotalAmount(), order.getStatus(), order.getCreatedAt(), order.getExpiresAt(), order.getPaidAt(),
                order.getClosedReason(),
                items.stream().map(item -> new TicketOrderItemResponse(item.getOrderItemId(), item.getZoneId(),
                        AllocationMode.valueOf(item.getAllocationMode()), item.getRequestedSeatId(), item.getQuantity(),
                        item.getAcceptNonAdjacent(), item.getUnitPrice())).toList());
    }
}
