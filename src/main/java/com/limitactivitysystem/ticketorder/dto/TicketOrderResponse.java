package com.limitactivitysystem.ticketorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Output contract only. Database timestamps and server-calculated prices are never request fields. */
public record TicketOrderResponse(
        Long orderId,
        String requestId,
        Long sessionId,
        BigDecimal totalAmount,
        String status,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        LocalDateTime paidAt,
        List<TicketOrderItemResponse> items) {
}
