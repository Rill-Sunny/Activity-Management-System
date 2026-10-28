package com.limitactivitysystem.activity.controller;

import com.limitactivitysystem.activity.dto.CreateActivityRequest;
import com.limitactivitysystem.activity.dto.CreateActivityResponse;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.service.PublisherActivityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/publisher/activities")
public class PublisherActivityController {

    private final PublisherActivityService publisherActivityService;

    public PublisherActivityController(PublisherActivityService publisherActivityService) {
        this.publisherActivityService = publisherActivityService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateActivityResponse createActivity(@Valid @RequestBody CreateActivityRequest request) {
        Activity activity = publisherActivityService.createActivity(request);
        return new CreateActivityResponse(activity.getActivityId(), activity.getStatus());
    }
}
