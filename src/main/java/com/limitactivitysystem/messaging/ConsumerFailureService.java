package com.limitactivitysystem.messaging;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsumerFailureService {
    private final ConsumerFailureMapper failures;

    public ConsumerFailureService(ConsumerFailureMapper failures) { this.failures = failures; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String consumerName, String eventId, int attempts, boolean failed, String reason) {
        if (eventId == null || eventId.length() != 36) { return; }
        String safeReason = reason == null ? "Unknown consumer failure" : reason;
        if (safeReason.length() > 1000) { safeReason = safeReason.substring(0, 1000); }
        failures.record(consumerName, eventId, Math.max(1, attempts), failed ? "FAILED" : "RETRYING", safeReason);
    }
}
