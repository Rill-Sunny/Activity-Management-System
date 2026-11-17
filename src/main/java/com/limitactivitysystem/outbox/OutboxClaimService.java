package com.limitactivitysystem.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxClaimService {
    private final OutboxEventMapper events;

    public OutboxClaimService(OutboxEventMapper events) { this.events = events; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimBatch(int batchSize, int maxAttempts, int leaseSeconds) {
        events.failExhaustedLeases(maxAttempts);
        List<OutboxEvent> due = events.findClaimable(batchSize, maxAttempts);
        List<OutboxEvent> claimed = new ArrayList<>(due.size());
        for (OutboxEvent event : due) {
            String leaseToken = UUID.randomUUID().toString();
            if (events.claim(event.getEventId(), leaseToken, leaseSeconds, maxAttempts) == 1) {
                event.setStatus("CLAIMED");
                event.setAttempts(event.getAttempts() + 1);
                event.setLeaseToken(leaseToken);
                claimed.add(event);
            }
        }
        return List.copyOf(claimed);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(OutboxEvent event) {
        if (events.markSent(event.getEventId(), event.getLeaseToken()) != 1) {
            throw new IllegalStateException("Outbox lease was lost before SENT update: " + event.getEventId());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(OutboxEvent event, int maxAttempts, int retryDelaySeconds, String reason) {
        return events.markPublishFailed(event.getEventId(), event.getLeaseToken(), maxAttempts,
                retryDelaySeconds, reason) == 1;
    }
}
