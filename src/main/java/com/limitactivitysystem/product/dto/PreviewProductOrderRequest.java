package com.limitactivitysystem.product.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public class PreviewProductOrderRequest {
    @NotEmpty
    @Size(max = 100)
    private List<@NotNull @Valid ProductOrderItemRequest> items;

    public List<ProductOrderItemRequest> getItems() { return items; }
    public void setItems(List<ProductOrderItemRequest> items) { this.items = items; }

    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Product order preview accepts only items");
    }
}
