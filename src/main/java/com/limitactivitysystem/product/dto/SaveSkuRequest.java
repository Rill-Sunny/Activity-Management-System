package com.limitactivitysystem.product.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record SaveSkuRequest(@NotBlank @Size(max=100) String name,
        @NotNull @DecimalMin("0.01") @Digits(integer=8,fraction=2) @JsonDeserialize(using=ProductRequestFormats.MoneyValue.class) BigDecimal price,
        @NotNull @PositiveOrZero @JsonDeserialize(using=ProductRequestFormats.IntegerValue.class) Integer stock) {
    @JsonAnySetter public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("Unknown SKU field: " + key); }
}
