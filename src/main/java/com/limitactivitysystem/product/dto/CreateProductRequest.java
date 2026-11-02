package com.limitactivitysystem.product.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;

public record CreateProductRequest(
        @NotNull @Positive @JsonDeserialize(using=ProductRequestFormats.LongValue.class) Long activityId,
        @NotBlank @Size(max=100) String name, @Size(max=1000) String description,
        @NotNull @Pattern(regexp="PUBLIC|TICKET_REQUIRED|PRODUCT_WHITELIST") String qualificationType,
        @Pattern(regexp="ACTIVITY|SESSION") String qualificationScope,
        @Positive @JsonDeserialize(using=ProductRequestFormats.LongValue.class) Long qualificationSessionId,
        @NotNull @Positive @JsonDeserialize(using=ProductRequestFormats.IntegerValue.class) Integer purchaseLimit,
        @NotNull @Pattern(regexp="STANDARD_7D|SPECIAL_NO_REASON_RETURN") String refundPolicy) {
    @JsonAnySetter public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("Unknown product field: " + key); }
}
