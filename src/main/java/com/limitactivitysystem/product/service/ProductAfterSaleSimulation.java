package com.limitactivitysystem.product.service;

import com.limitactivitysystem.product.dto.CreateProductAfterSaleRequest;

/** Normalizes the optional local inspection inputs and keeps old resalable-only payloads deterministic. */
final class ProductAfterSaleSimulation {
    private ProductAfterSaleSimulation() { }

    static boolean inspectionPassed(CreateProductAfterSaleRequest.Item item) {
        if (item.simulateInspectionPassed() != null) { return item.simulateInspectionPassed(); }
        return item.simulateResalable() == null || item.simulateResalable();
    }

    static boolean resalable(CreateProductAfterSaleRequest.Item item) {
        if (item.simulateResalable() != null) { return item.simulateResalable(); }
        return inspectionPassed(item);
    }
}
