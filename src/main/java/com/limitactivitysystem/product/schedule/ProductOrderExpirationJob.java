package com.limitactivitysystem.product.schedule;

import com.limitactivitysystem.product.mapper.ProductOrderExpirationMapper;
import com.limitactivitysystem.product.service.ProductOrderClosureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class ProductOrderExpirationJob {
    private static final Logger log = LoggerFactory.getLogger(ProductOrderExpirationJob.class);
    private final ProductOrderExpirationMapper orders;
    private final ProductOrderClosureService closing;
    private final int batchSize;

    public ProductOrderExpirationJob(ProductOrderExpirationMapper orders, ProductOrderClosureService closing, int batchSize) {
        if (batchSize <= 0) { throw new IllegalArgumentException("Expiration batch size must be positive"); }
        this.orders = orders; this.closing = closing; this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${product-order.expiration.scan-delay-ms:5000}",
            initialDelayString = "${product-order.expiration.initial-delay-ms:5000}")
    public void closeExpiredOrders() {
        for (Long orderId : orders.findExpiredPendingOrderIds(batchSize)) {
            try { closing.closeExpiredOrder(orderId); }
            catch (RuntimeException failure) { log.warn("Could not close expired product order: orderId={}", orderId, failure); }
        }
    }
}
