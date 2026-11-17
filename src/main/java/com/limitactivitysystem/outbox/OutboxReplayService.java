package com.limitactivitysystem.outbox;

import com.limitactivitysystem.messaging.ConsumerFailureMapper;
import com.limitactivitysystem.messaging.ConsumerProcessMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OutboxReplayService {
    private final OutboxEventMapper events;
    private final ConsumerProcessMapper processed;
    private final ConsumerFailureMapper failures;

    public OutboxReplayService(OutboxEventMapper events, ConsumerProcessMapper processed,
                               ConsumerFailureMapper failures) {
        this.events = events;
        this.processed = processed;
        this.failures = failures;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requestReplay(String eventId) {
        if (processed.existsForEvent(eventId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Event already committed by a consumer; automatic replay would be a duplicate");
        }
        if (events.requestReplay(eventId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Event is not in FAILED state and has no failed consumer attempt");
        }
        failures.deleteByEvent(eventId);
    }
}
