package com.limitactivitysystem.activity.service;

import com.limitactivitysystem.activity.dto.CreateActivityRequest;
import com.limitactivitysystem.activity.exception.ActivityErrorCode;
import com.limitactivitysystem.activity.exception.ActivityException;
import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.CurrentUserProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class PublisherActivityService {

    private final ActivityMapper activityMapper;
    private final CurrentUserProvider currentUserProvider;

    public PublisherActivityService(ActivityMapper activityMapper, CurrentUserProvider currentUserProvider) {
        this.activityMapper = activityMapper;
        this.currentUserProvider = currentUserProvider;
    }

    public Activity createActivity(CreateActivityRequest request) {
        AppUser publisher = currentUserProvider.getCurrentActiveUser();
        if (!"PUBLISHER".equals(publisher.getRole())) {
            throw new AccessDeniedException("Insufficient permission");
        }
        if (!request.getOpenTime().isBefore(request.getCloseTime())) {
            throw new ActivityException(ActivityErrorCode.INVALID_ACTIVITY_WINDOW,
                    "openTime must be earlier than closeTime");
        }

        Activity activity = new Activity();
        activity.setPublisherId(publisher.getId());
        activity.setName(request.getName());
        activity.setDescription(request.getDescription());
        activity.setType(request.getType());
        activity.setOpenTime(request.getOpenTime());
        activity.setCloseTime(request.getCloseTime());
        activity.setStatus("DRAFT");
        activityMapper.insert(activity);
        return activity;
    }
}
