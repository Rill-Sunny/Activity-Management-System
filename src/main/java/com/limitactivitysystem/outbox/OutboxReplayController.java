package com.limitactivitysystem.outbox;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/outbox-events")
public class OutboxReplayController {
    private final OutboxReplayService replay;

    public OutboxReplayController(OutboxReplayService replay) { this.replay = replay; }

    @PostMapping("/{eventId}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReplayResponse replay(@PathVariable String eventId) {
        replay.requestReplay(eventId);
        return new ReplayResponse(eventId, "PENDING");
    }

    public record ReplayResponse(String eventId, String status) { }
}
