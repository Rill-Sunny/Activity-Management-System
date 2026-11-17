package com.limitactivitysystem.qualification.model;

import java.time.LocalDateTime;

public class TicketQualificationAfterSaleTask {
    private Long taskId;
    private String sourceEventId;
    private Long productOrderItemId;
    private String qualificationScope;
    private String status;
    private int attemptCount;
    private LocalDateTime nextAttemptAt;
    private String failureReason;
    private Long orderId;
    private Long buyerId;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getSourceEventId() { return sourceEventId; }
    public void setSourceEventId(String sourceEventId) { this.sourceEventId = sourceEventId; }
    public Long getProductOrderItemId() { return productOrderItemId; }
    public void setProductOrderItemId(Long productOrderItemId) { this.productOrderItemId = productOrderItemId; }
    public String getQualificationScope() { return qualificationScope; }
    public void setQualificationScope(String qualificationScope) { this.qualificationScope = qualificationScope; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getBuyerId() { return buyerId; }
    public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }
}
