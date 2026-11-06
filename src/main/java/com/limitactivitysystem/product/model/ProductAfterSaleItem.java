package com.limitactivitysystem.product.model;

import java.time.LocalDateTime;

public class ProductAfterSaleItem {
    private Long afterSaleItemId;
    private Long afterSaleRequestId;
    private Long orderItemId;
    private Long skuId;
    private String reasonCode;
    private Integer requestedQuantity;
    private boolean simulateInspectionPassed;
    private boolean simulateResalable;
    private String status;
    private Integer returnedQuantity;
    private Integer refundedQuantity;
    private LocalDateTime returnedAt;
    private String inspectionResult;
    private Boolean isResalable;
    private LocalDateTime createdAt;

    public Long getAfterSaleItemId() { return afterSaleItemId; }
    public void setAfterSaleItemId(Long value) { this.afterSaleItemId = value; }
    public Long getAfterSaleRequestId() { return afterSaleRequestId; }
    public void setAfterSaleRequestId(Long value) { this.afterSaleRequestId = value; }
    public Long getOrderItemId() { return orderItemId; }
    public void setOrderItemId(Long value) { this.orderItemId = value; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long value) { this.skuId = value; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String value) { this.reasonCode = value; }
    public Integer getRequestedQuantity() { return requestedQuantity; }
    public void setRequestedQuantity(Integer value) { this.requestedQuantity = value; }
    public boolean isSimulateInspectionPassed() { return simulateInspectionPassed; }
    public void setSimulateInspectionPassed(boolean value) { this.simulateInspectionPassed = value; }
    public boolean isSimulateResalable() { return simulateResalable; }
    public void setSimulateResalable(boolean value) { this.simulateResalable = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public Integer getReturnedQuantity() { return returnedQuantity; }
    public void setReturnedQuantity(Integer value) { this.returnedQuantity = value; }
    public Integer getRefundedQuantity() { return refundedQuantity; }
    public void setRefundedQuantity(Integer value) { this.refundedQuantity = value; }
    public LocalDateTime getReturnedAt() { return returnedAt; }
    public void setReturnedAt(LocalDateTime value) { this.returnedAt = value; }
    public String getInspectionResult() { return inspectionResult; }
    public void setInspectionResult(String value) { this.inspectionResult = value; }
    public Boolean getIsResalable() { return isResalable; }
    public void setIsResalable(Boolean value) { this.isResalable = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
}
