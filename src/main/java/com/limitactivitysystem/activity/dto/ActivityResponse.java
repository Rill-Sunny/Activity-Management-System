package com.limitactivitysystem.activity.dto;

import com.limitactivitysystem.activity.model.Activity;

import java.time.LocalDateTime;

public record ActivityResponse(Long activityId, String name, String description, String type, String status,
                               LocalDateTime openTime, LocalDateTime closeTime) {

    public static ActivityResponse from(Activity activity) {
        return new ActivityResponse(activity.getActivityId(), activity.getName(), activity.getDescription(),
                activity.getType(), activity.getStatus(), activity.getOpenTime(), activity.getCloseTime());
    }
}
