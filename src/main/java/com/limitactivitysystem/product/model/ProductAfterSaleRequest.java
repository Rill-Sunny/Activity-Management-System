package com.limitactivitysystem.product.model;

import java.time.LocalDateTime;

public class ProductAfterSaleRequest {
    private Long afterSaleRequestId;
    private Long orderId;
    private Long buyerId;
    private String requestId;
    private String reason;
    private LocalDateTime requestedAt;
    private String status;
    private String failureReason;

    public Long getAfterSaleRequestId() { return afterSaleRequestId; }
    public void setAfterSaleRequestId(Long value) { this.afterSaleRequestId = value; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long value) { this.orderId = value; }
    public Long getBuyerId() { return buyerId; }
    public void setBuyerId(Long value) { this.buyerId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { this.requestId = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { this.reason = value; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime value) { this.requestedAt = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String value) { this.failureReason = value; }
}
