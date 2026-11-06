package com.limitactivitysystem.product.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ProductFulfillmentRequest(@NotNull @Positive Long orderItemId,
                                        @NotNull @Positive Integer quantity) { }
