package com.limitactivitysystem.ticketorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record TicketPaymentResponse(Long orderId, Long sessionId, BigDecimal totalAmount,
                                    String status, LocalDateTime paidAt, List<TicketResponse> tickets) {
    public TicketPaymentResponse { tickets = List.copyOf(tickets); }
}
