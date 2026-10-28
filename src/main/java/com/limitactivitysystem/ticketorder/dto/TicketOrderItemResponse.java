package com.limitactivitysystem.ticketorder.dto;

import java.math.BigDecimal;

public record TicketOrderItemResponse(
        Long orderItemId,
        Long zoneId,
        AllocationMode allocationMode,
        Long requestedSeatId,
        Integer quantity,
        Boolean acceptNonAdjacent,
        BigDecimal unitPrice) {
}
