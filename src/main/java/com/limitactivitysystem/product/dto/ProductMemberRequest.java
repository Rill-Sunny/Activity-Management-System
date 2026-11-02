package com.limitactivitysystem.product.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;

public record ProductMemberRequest(@NotNull @Positive @JsonDeserialize(using=ProductRequestFormats.LongValue.class) Long userId) {
    @JsonAnySetter public void rejectUnknown(String key,Object value) { throw new IllegalArgumentException("Only the target userId is accepted"); }
}
