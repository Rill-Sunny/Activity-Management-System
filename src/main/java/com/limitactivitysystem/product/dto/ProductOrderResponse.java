package com.limitactivitysystem.product.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ProductOrderResponse(Long orderId, String requestId, Long publisherId, BigDecimal totalAmount,
                                   String status, LocalDateTime createdAt, LocalDateTime expiresAt, LocalDateTime paidAt,
                                   String closedReason,
                                   List<Item> items) {
    public record Item(Long productId, Long skuId, String productName, String skuName,
                       Integer quantity, BigDecimal unitPrice, BigDecimal subtotal,
                       Long activityId, String qualificationType, String qualificationScope,
                       Long qualificationSessionId, String refundPolicyTemplate, Integer refundPolicyVersion,
                       boolean specialPolicyAcknowledged) {
    }
}
