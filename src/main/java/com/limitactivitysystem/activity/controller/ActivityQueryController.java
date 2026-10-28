package com.limitactivitysystem.activity.controller;

import com.limitactivitysystem.activity.dto.ActivityResponse;
import com.limitactivitysystem.activity.service.ActivityQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/activities")
public class ActivityQueryController {

    private final ActivityQueryService activityQueryService;

    public ActivityQueryController(ActivityQueryService activityQueryService) {
        this.activityQueryService = activityQueryService;
    }

    @GetMapping
    public List<ActivityResponse> listActivities() {
        return activityQueryService.listPublished();
    }

    @GetMapping("/{id}")
    public ActivityResponse getActivity(@PathVariable("id") Long id) {
        return activityQueryService.getPublished(id);
    }
}
