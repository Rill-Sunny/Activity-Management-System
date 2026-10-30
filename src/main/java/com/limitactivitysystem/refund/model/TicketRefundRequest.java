package com.limitactivitysystem.refund.model;

import java.time.LocalDateTime;

public class TicketRefundRequest {
    private Long refundId;
    private Long buyerId;
    private Long orderId;
    private String requestId;
    private String reason;
    private LocalDateTime requestedAt;
    private String status;
    private LocalDateTime createdAt;
    private String policyVersion;
    private LocalDateTime openTimeSnapshot;
    private LocalDateTime startTimeSnapshot;
    public Long getRefundId() { return refundId; }
    public void setRefundId(Long refundId) { this.refundId = refundId; }
    public Long getBuyerId() { return buyerId; }
    public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public LocalDateTime getOpenTimeSnapshot() { return openTimeSnapshot; }
    public void setOpenTimeSnapshot(LocalDateTime openTimeSnapshot) { this.openTimeSnapshot = openTimeSnapshot; }
    public LocalDateTime getStartTimeSnapshot() { return startTimeSnapshot; }
    public void setStartTimeSnapshot(LocalDateTime startTimeSnapshot) { this.startTimeSnapshot = startTimeSnapshot; }
}
