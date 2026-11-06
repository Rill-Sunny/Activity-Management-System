package com.limitactivitysystem.product.service;

import java.time.LocalDateTime;

final class ProductAfterSalePolicy {
    private ProductAfterSalePolicy() { }

    /** The 7-day period is 168 elapsed hours and includes its start but excludes its exact end. */
    static boolean withinStandardSevenDayWindow(LocalDateTime receivedAt, LocalDateTime now) {
        return receivedAt != null && now != null && !now.isBefore(receivedAt)
                && now.isBefore(receivedAt.plusDays(7));
    }
}
