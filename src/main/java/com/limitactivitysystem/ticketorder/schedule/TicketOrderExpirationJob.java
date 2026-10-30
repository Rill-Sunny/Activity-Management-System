package com.limitactivitysystem.ticketorder.schedule;

import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.service.TicketOrderCloseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class TicketOrderExpirationJob {
    private static final Logger log = LoggerFactory.getLogger(TicketOrderExpirationJob.class);
    private final TicketOrderMapper orders;
    private final TicketOrderCloseService closing;
    private final int batchSize;

    public TicketOrderExpirationJob(TicketOrderMapper orders, TicketOrderCloseService closing, int batchSize) {
        if (batchSize <= 0) { throw new IllegalArgumentException("Expiration batch size must be positive"); }
        this.orders = orders;
        this.closing = closing;
        this.batchSize = batchSize;
    }

    // No enclosing transaction: scanning holds no row locks and each close commits independently.
    @Scheduled(fixedDelayString = "${ticket-order.expiration.scan-delay-ms:5000}",
            initialDelayString = "${ticket-order.expiration.initial-delay-ms:5000}")
    public void closeExpiredOrders() {
        for (Long orderId : orders.findExpiredPendingOrderIds(batchSize)) {
            try {
                closing.closeExpiredOrder(orderId);
            } catch (RuntimeException exception) {
                // A failed row stays eligible for the next scan without aborting the rest of this batch.
                log.warn("Could not close expired ticket order: orderId={}", orderId, exception);
            }
        }
    }
}
