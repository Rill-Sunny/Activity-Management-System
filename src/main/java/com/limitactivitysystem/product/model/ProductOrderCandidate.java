package com.limitactivitysystem.product.model;

import java.math.BigDecimal;

public record ProductOrderCandidate(
        Long productId,
        Long publisherId,
        Long activityId,
        String productName,
        String productStatus,
        String qualificationType,
        String qualificationScope,
        Long qualificationSessionId,
        String refundPolicyTemplate,
        Integer refundPolicyVersion,
        Integer purchaseLimit,
        Long skuId,
        String skuName,
        BigDecimal unitPrice,
        Integer remainsStock,
        String skuStatus) {
}
