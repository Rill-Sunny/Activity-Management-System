package com.limitactivitysystem.activity.service;

import com.limitactivitysystem.activity.dto.ActivityResponse;
import com.limitactivitysystem.activity.exception.ActivityErrorCode;
import com.limitactivitysystem.activity.exception.ActivityException;
import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.cache.CacheAsideService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ActivityQueryService {

    private final ActivityMapper activityMapper;
    private final CacheAsideService cache;

    public ActivityQueryService(ActivityMapper activityMapper, CacheAsideService cache) {
        this.activityMapper = activityMapper;
        this.cache = cache;
    }

    // Publication controls visibility; the sale/reservation window is checked when creating orders.
    public List<ActivityResponse> listPublished() {
        return activityMapper.findPublished().stream().map(ActivityResponse::from).toList();
    }

    public ActivityResponse getPublished(Long id) {
        ActivityResponse response = cache.get("las:activity:" + id, ActivityResponse.class, () -> {
            Activity activity = activityMapper.findPublishedById(id);
            return activity == null ? null : ActivityResponse.from(activity);
        });
        if (response == null) {
            throw new ActivityException(ActivityErrorCode.ACTIVITY_NOT_FOUND, "Activity not found");
        }
        return response;
    }
}
