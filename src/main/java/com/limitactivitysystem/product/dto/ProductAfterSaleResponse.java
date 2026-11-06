package com.limitactivitysystem.product.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ProductAfterSaleResponse(Long afterSaleRequestId, Long orderId, String requestId,
        String reason, LocalDateTime requestedAt, String status, String failureReason, List<Item> items) {
    public record Item(Long orderItemId, Long skuId, String reason, Integer requestedQuantity,
                       Integer returnedQuantity, Integer refundedQuantity, LocalDateTime returnedAt, String inspectionResult,
                       Boolean resalable) { }
}
