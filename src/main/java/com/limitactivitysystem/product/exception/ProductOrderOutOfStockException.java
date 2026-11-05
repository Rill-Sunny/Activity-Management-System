package com.limitactivitysystem.product.exception;

import java.util.List;

public class ProductOrderOutOfStockException extends RuntimeException {
    private final Long orderId;
    private final List<UnavailableItem> unavailableItems;

    public ProductOrderOutOfStockException(Long orderId, List<UnavailableItem> unavailableItems) {
        super("One or more ordered SKUs no longer have enough stock");
        this.orderId = orderId;
        this.unavailableItems = List.copyOf(unavailableItems);
    }

    public Long orderId() { return orderId; }
    public List<UnavailableItem> unavailableItems() { return unavailableItems; }

    public record UnavailableItem(Long skuId, Integer requestedQuantity, Integer availableStock, String status) { }
}
