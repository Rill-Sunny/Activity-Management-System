package com.limitactivitysystem.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelayService {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
    private final OutboxClaimService claims;
    private final OutboxPublisher publisher;
    private final int batchSize;
    private final int maxAttempts;
    private final int leaseSeconds;

    public OutboxRelayService(OutboxClaimService claims, OutboxPublisher publisher,
            @Value("${outbox.relay.batch-size:50}") int batchSize,
            @Value("${outbox.relay.max-attempts:8}") int maxAttempts,
            @Value("${outbox.relay.lease-seconds:60}") int leaseSeconds) {
        this.claims = claims;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.leaseSeconds = leaseSeconds;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.scan-delay-ms:1000}",
            initialDelayString = "${outbox.relay.initial-delay-ms:1000}")
    public void publishClaimedEvents() {
        for (OutboxEvent event : claims.claimBatch(batchSize, maxAttempts, leaseSeconds)) {
            try {
                // claimBatch committed before network I/O; only a short completion transaction follows.
                publisher.publish(event);
                claims.markSent(event);
            } catch (RuntimeException failure) {
                int retryDelay = Math.min(300, 1 << Math.min(8, Math.max(0, event.getAttempts() - 1)));
                String reason = compactReason(failure);
                try {
                    boolean recorded = claims.markFailed(event, maxAttempts, retryDelay, reason);
                    if (recorded) {
                        log.warn("Outbox publish failed: eventId={} attempt={} retryDelaySeconds={} reason={}",
                                event.getEventId(), event.getAttempts(), retryDelay, reason);
                    } else {
                        log.warn("Outbox claim expired before failure update: eventId={}", event.getEventId());
                    }
                } catch (RuntimeException persistenceFailure) {
                    failure.addSuppressed(persistenceFailure);
                    log.error("Could not record outbox publish failure; lease expiry will recover it: eventId={} errorType={}",
                            event.getEventId(), persistenceFailure.getClass().getSimpleName());
                }
            }
        }
    }

    private String compactReason(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) { root = root.getCause(); }
        String text = root.getClass().getSimpleName();
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }
}
