package com.limitactivitysystem.product.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAfterSalePolicyTest {
    @Test
    void standardSevenDayWindowIncludesReceiptAndLastMillisecondButExcludesExactEndpoint() {
        LocalDateTime receivedAt = LocalDateTime.of(2026, 11, 5, 10, 30, 0, 123_000_000);
        LocalDateTime endpoint = receivedAt.plusDays(7);

        assertThat(ProductAfterSalePolicy.withinStandardSevenDayWindow(receivedAt, receivedAt)).isTrue();
        assertThat(ProductAfterSalePolicy.withinStandardSevenDayWindow(receivedAt, endpoint.minusNanos(1_000_000))).isTrue();
        assertThat(ProductAfterSalePolicy.withinStandardSevenDayWindow(receivedAt, endpoint)).isFalse();
        assertThat(ProductAfterSalePolicy.withinStandardSevenDayWindow(receivedAt, endpoint.plusNanos(1_000_000))).isFalse();
        assertThat(ProductAfterSalePolicy.withinStandardSevenDayWindow(receivedAt, receivedAt.minusNanos(1_000_000))).isFalse();
    }
}
