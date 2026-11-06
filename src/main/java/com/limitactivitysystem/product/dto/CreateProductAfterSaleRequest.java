package com.limitactivitysystem.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateProductAfterSaleRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Positive Long orderId,
        @NotEmpty @Size(max = 100) List<@Valid Item> items) {
    public record Item(@NotNull @Positive Long orderItemId,
                       @NotNull @Positive Integer quantity,
                       @NotBlank @Pattern(regexp = "NO_REASON|QUALITY_PROBLEM|TICKET_QUALIFICATION_LOST|ACTIVITY_CANCELLED") String reason,
                       Boolean simulateInspectionPassed,
                       Boolean simulateResalable) { }
}
