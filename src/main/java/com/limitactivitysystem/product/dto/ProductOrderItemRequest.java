package com.limitactivitysystem.product.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class ProductOrderItemRequest {
    @NotNull
    @Positive
    private Long skuId;

    @NotNull
    @Positive
    private Integer quantity;

    private boolean acknowledgeSpecialPolicy;

    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public boolean isAcknowledgeSpecialPolicy() { return acknowledgeSpecialPolicy; }
    public void setAcknowledgeSpecialPolicy(boolean acknowledgeSpecialPolicy) {
        this.acknowledgeSpecialPolicy = acknowledgeSpecialPolicy;
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Product order items accept only skuId, quantity and acknowledgeSpecialPolicy");
    }
}
