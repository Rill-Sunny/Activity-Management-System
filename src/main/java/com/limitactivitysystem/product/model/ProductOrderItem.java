package com.limitactivitysystem.product.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ProductOrderItem {
    private Long orderItemId;
    private Long orderId;
    private Long productId;
    private Long skuId;
    private Integer quantity;
    private BigDecimal unitPrice;
    private String productNameSnapshot;
    private String skuNameSnapshot;
    private Long activityIdSnapshot;
    private String qualificationTypeSnapshot;
    private String qualificationScopeSnapshot;
    private Long qualificationSessionIdSnapshot;
    private Long qualificationGuardVersionSnapshot;
    private String refundPolicyTemplateSnapshot;
    private Integer refundPolicyVersionSnapshot;
    private boolean specialPolicyAcknowledged;
    private LocalDateTime policyAcknowledgedAt;
    private String fulfillmentStatus;
    private Integer shippedQuantity;
    private Integer receivedQuantity;
    private LocalDateTime shippedAt;
    private LocalDateTime receivedAt;
    private Integer refundPendingQuantity;
    private Integer refundedQuantity;
    private Integer returnedQuantity;
    private Integer restockedQuantity;

    public Long getOrderItemId() { return orderItemId; }
    public void setOrderItemId(Long orderItemId) { this.orderItemId = orderItemId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public String getProductNameSnapshot() { return productNameSnapshot; }
    public void setProductNameSnapshot(String productNameSnapshot) { this.productNameSnapshot = productNameSnapshot; }
    public String getSkuNameSnapshot() { return skuNameSnapshot; }
    public void setSkuNameSnapshot(String skuNameSnapshot) { this.skuNameSnapshot = skuNameSnapshot; }
    public Long getActivityIdSnapshot() { return activityIdSnapshot; }
    public void setActivityIdSnapshot(Long activityIdSnapshot) { this.activityIdSnapshot = activityIdSnapshot; }
    public String getQualificationTypeSnapshot() { return qualificationTypeSnapshot; }
    public void setQualificationTypeSnapshot(String qualificationTypeSnapshot) { this.qualificationTypeSnapshot = qualificationTypeSnapshot; }
    public String getQualificationScopeSnapshot() { return qualificationScopeSnapshot; }
    public void setQualificationScopeSnapshot(String qualificationScopeSnapshot) { this.qualificationScopeSnapshot = qualificationScopeSnapshot; }
    public Long getQualificationSessionIdSnapshot() { return qualificationSessionIdSnapshot; }
    public void setQualificationSessionIdSnapshot(Long qualificationSessionIdSnapshot) { this.qualificationSessionIdSnapshot = qualificationSessionIdSnapshot; }
    public Long getQualificationGuardVersionSnapshot() { return qualificationGuardVersionSnapshot; }
    public void setQualificationGuardVersionSnapshot(Long qualificationGuardVersionSnapshot) { this.qualificationGuardVersionSnapshot = qualificationGuardVersionSnapshot; }
    public String getRefundPolicyTemplateSnapshot() { return refundPolicyTemplateSnapshot; }
    public void setRefundPolicyTemplateSnapshot(String refundPolicyTemplateSnapshot) { this.refundPolicyTemplateSnapshot = refundPolicyTemplateSnapshot; }
    public Integer getRefundPolicyVersionSnapshot() { return refundPolicyVersionSnapshot; }
    public void setRefundPolicyVersionSnapshot(Integer refundPolicyVersionSnapshot) { this.refundPolicyVersionSnapshot = refundPolicyVersionSnapshot; }
    public boolean isSpecialPolicyAcknowledged() { return specialPolicyAcknowledged; }
    public void setSpecialPolicyAcknowledged(boolean specialPolicyAcknowledged) { this.specialPolicyAcknowledged = specialPolicyAcknowledged; }
    public LocalDateTime getPolicyAcknowledgedAt() { return policyAcknowledgedAt; }
    public void setPolicyAcknowledgedAt(LocalDateTime policyAcknowledgedAt) { this.policyAcknowledgedAt = policyAcknowledgedAt; }
    public String getFulfillmentStatus() { return fulfillmentStatus; }
    public void setFulfillmentStatus(String fulfillmentStatus) { this.fulfillmentStatus = fulfillmentStatus; }
    public Integer getShippedQuantity() { return shippedQuantity; }
    public void setShippedQuantity(Integer shippedQuantity) { this.shippedQuantity = shippedQuantity; }
    public Integer getReceivedQuantity() { return receivedQuantity; }
    public void setReceivedQuantity(Integer receivedQuantity) { this.receivedQuantity = receivedQuantity; }
    public LocalDateTime getShippedAt() { return shippedAt; }
    public void setShippedAt(LocalDateTime shippedAt) { this.shippedAt = shippedAt; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
    public Integer getRefundPendingQuantity() { return refundPendingQuantity; }
    public void setRefundPendingQuantity(Integer refundPendingQuantity) { this.refundPendingQuantity = refundPendingQuantity; }
    public Integer getRefundedQuantity() { return refundedQuantity; }
    public void setRefundedQuantity(Integer refundedQuantity) { this.refundedQuantity = refundedQuantity; }
    public Integer getReturnedQuantity() { return returnedQuantity; }
    public void setReturnedQuantity(Integer returnedQuantity) { this.returnedQuantity = returnedQuantity; }
    public Integer getRestockedQuantity() { return restockedQuantity; }
    public void setRestockedQuantity(Integer restockedQuantity) { this.restockedQuantity = restockedQuantity; }
}
