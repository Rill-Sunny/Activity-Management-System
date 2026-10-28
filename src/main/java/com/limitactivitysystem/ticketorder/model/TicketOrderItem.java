package com.limitactivitysystem.ticketorder.model;

import java.math.BigDecimal;

public class TicketOrderItem {
    private Long orderItemId;
    private Long orderId;
    private Long zoneId;
    private String allocationMode;
    private Long requestedSeatId;
    private Integer quantity;
    private BigDecimal unitPrice;
    private Boolean acceptNonAdjacent;

    public Long getOrderItemId() { return orderItemId; }
    public void setOrderItemId(Long orderItemId) { this.orderItemId = orderItemId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getZoneId() { return zoneId; }
    public void setZoneId(Long zoneId) { this.zoneId = zoneId; }
    public String getAllocationMode() { return allocationMode; }
    public void setAllocationMode(String allocationMode) { this.allocationMode = allocationMode; }
    public Long getRequestedSeatId() { return requestedSeatId; }
    public void setRequestedSeatId(Long requestedSeatId) { this.requestedSeatId = requestedSeatId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public Boolean getAcceptNonAdjacent() { return acceptNonAdjacent; }
    public void setAcceptNonAdjacent(Boolean acceptNonAdjacent) { this.acceptNonAdjacent = acceptNonAdjacent; }
}
