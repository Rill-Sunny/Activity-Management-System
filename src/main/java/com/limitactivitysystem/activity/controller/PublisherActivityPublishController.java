package com.limitactivitysystem.activity.controller;

import com.limitactivitysystem.activity.service.PublisherActivityPublishService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/publisher/activities/{activityId}/publish")
public class PublisherActivityPublishController {

    private final PublisherActivityPublishService publishService;

    public PublisherActivityPublishController(PublisherActivityPublishService publishService) {
        this.publishService = publishService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publishActivity(@PathVariable("activityId") Long activityId) {
        publishService.publishActivity(activityId);
    }
}
