package com.limitactivitysystem.activity.controller;

import com.limitactivitysystem.activity.dto.CreateActivitySessionRequest;
import com.limitactivitysystem.activity.dto.CreateActivitySessionResponse;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.service.PublisherActivitySessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/publisher/activities/{activityId}/sessions")
public class PublisherActivitySessionController {

    private final PublisherActivitySessionService sessionService;

    public PublisherActivitySessionController(PublisherActivitySessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateActivitySessionResponse createSession(@PathVariable("activityId") Long activityId,
                                                        @Valid @RequestBody CreateActivitySessionRequest request) {
        ActivitySession session = sessionService.createSession(activityId, request);
        return new CreateActivitySessionResponse(session.getActivitySessionId(), session.getStatus());
    }
}
