package com.limitactivitysystem.product.dto;

import java.math.BigDecimal;
import java.util.List;

public record ProductOrderPreviewResponse(boolean available, BigDecimal totalAmount, List<Item> items) {
    public record Item(Long skuId, Long productId, String productName, String skuName,
                       Integer requestedQuantity, Integer availableStock, String status,
                       BigDecimal unitPrice, BigDecimal subtotal, boolean requiresPolicyAcknowledgement) {
    }
}
