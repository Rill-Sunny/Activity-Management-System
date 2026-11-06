package com.limitactivitysystem.product.service;

/** Signals that request and quantity acceptance committed only in the failed transaction and needs a durable failure record. */
final class ProductAfterSaleAcceptedFailure extends RuntimeException {
    ProductAfterSaleAcceptedFailure(RuntimeException cause) {
        super("Accepted product after-sale transaction failed", cause);
    }
}
