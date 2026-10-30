package com.limitactivitysystem.refund.dto;

import java.time.LocalDateTime;
import java.util.List;

public record TicketRefundResponse(Long refundId, Long buyerId, Long orderId, String requestId, String reason,
                                   LocalDateTime requestedAt, String status, LocalDateTime createdAt,
                                   List<TicketRefundItemResponse> items) { }
