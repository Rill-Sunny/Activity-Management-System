package com.limitactivitysystem.product.model;

import java.time.LocalDateTime;

public record Product(Long productId, Long publisherId, Long activityId, String name, String description,
        String status, String qualificationType, String qualificationScope, Long qualificationSessionId,
        String refundPolicy, Integer refundPolicyVersion, Integer purchaseLimit, LocalDateTime firstOnSaleAt,
        LocalDateTime createdAt, LocalDateTime updatedAt) { }
