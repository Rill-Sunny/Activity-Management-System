package com.limitactivitysystem.product.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Sku(Long skuId, Long productId, String name, BigDecimal price, Integer totalStock,
        Integer remainsStock, String status, LocalDateTime createdAt, LocalDateTime updatedAt) { }
