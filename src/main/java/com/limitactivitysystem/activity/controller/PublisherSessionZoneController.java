package com.limitactivitysystem.activity.controller;

import com.limitactivitysystem.activity.dto.CreateSessionZoneRequest;
import com.limitactivitysystem.activity.dto.CreateSessionZoneResponse;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.activity.service.PublisherSessionZoneService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/publisher/sessions/{sessionId}/zones")
public class PublisherSessionZoneController {

    private final PublisherSessionZoneService zoneService;

    public PublisherSessionZoneController(PublisherSessionZoneService zoneService) {
        this.zoneService = zoneService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSessionZoneResponse createZone(@PathVariable("sessionId") Long sessionId,
                                                @Valid @RequestBody CreateSessionZoneRequest request) {
        SessionZone zone = zoneService.createZone(sessionId, request);
        return new CreateSessionZoneResponse(zone.getZoneId(), zone.getTotalAmount(), zone.getRemainsAmount());
    }
}
