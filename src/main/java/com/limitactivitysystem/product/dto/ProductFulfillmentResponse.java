package com.limitactivitysystem.product.dto;

import java.time.LocalDateTime;

public record ProductFulfillmentResponse(Long orderId, Long orderItemId, Long skuId,
        Integer shippedQuantity, Integer receivedQuantity, String status,
        LocalDateTime shippedAt, LocalDateTime receivedAt) { }
