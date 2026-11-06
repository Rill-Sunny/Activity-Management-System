package com.limitactivitysystem.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class CacheInvalidationListener {
    private static final Logger LOG = LoggerFactory.getLogger(CacheInvalidationListener.class);
    private final CacheInvalidationDelivery delivery;

    public CacheInvalidationListener(CacheInvalidationDelivery delivery) {
        this.delivery = delivery;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(CacheInvalidationRecorded event) {
        try {
            delivery.deliver(event.taskId());
        } catch (RuntimeException failure) {
            // The durable task stays pending for the scheduled scanner.
            LOG.warn("Immediate cache invalidation failed taskId={} cause={}", event.taskId(),
                    failure.getClass().getSimpleName());
        }
    }
}
