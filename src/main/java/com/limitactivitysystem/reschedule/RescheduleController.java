package com.limitactivitysystem.reschedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class RescheduleController {
    private final RescheduleService service;
    private final RescheduleRefundService refunds;
    public RescheduleController(RescheduleService service,RescheduleRefundService refunds) { this.service=service; this.refunds=refunds; }
    @PostMapping("/publisher/sessions/{sessionId}/reschedules")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@PathVariable @Positive long sessionId,@RequestBody @Valid RescheduleRequests.Create input) {
        return service.view(service.create(sessionId,input));
    }
    @GetMapping("/publisher/sessions/{sessionId}/reschedules")
    public List<Map<String,Object>> history(@PathVariable @Positive long sessionId,
            @RequestParam(defaultValue="9223372036854775807") @Positive long beforeId) { return service.publisherHistory(sessionId,beforeId); }
    @GetMapping("/reschedules")
    public List<Map<String,Object>> mine(@RequestParam(defaultValue="9223372036854775807") @Positive long beforeId) { return service.mine(beforeId); }
    @GetMapping("/reschedules/{eventId}")
    public Map<String,Object> detail(@PathVariable @Positive long eventId) { return service.detail(eventId); }
    @PostMapping("/reschedules/{eventId}/confirm")
    public List<Map<String,Object>> confirm(@PathVariable @Positive long eventId,@RequestBody @Valid RescheduleRequests.Confirm input) {
        return service.confirm(eventId,input);
    }
    @PostMapping("/reschedules/{eventId}/special-refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public RescheduleRefundService.Result refund(@PathVariable @Positive long eventId,@RequestBody @Valid RescheduleRequests.Refund input) {
        return refunds.submit(eventId,input);
    }
}
