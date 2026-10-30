package com.limitactivitysystem.refund.dto;

import java.math.BigDecimal;

public record TicketRefundItemResponse(Long refundItemId, Long refundId, Long ticketId,
                                      BigDecimal originalPrice, BigDecimal feeRate, BigDecimal refundAmount,
                                      String status, String failureReason) { }
