package com.limitactivitysystem.product.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public class ConfirmProductOrderRequest {
    @NotBlank
    @Size(max = 64)
    private String requestId;

    @NotEmpty
    @Size(max = 100)
    private List<@NotNull @Valid ProductOrderItemRequest> items;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public List<ProductOrderItemRequest> getItems() { return items; }
    public void setItems(List<ProductOrderItemRequest> items) { this.items = items; }

    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Product order confirmation accepts only requestId and items");
    }
}
