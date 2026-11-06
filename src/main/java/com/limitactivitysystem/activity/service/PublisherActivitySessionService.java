package com.limitactivitysystem.activity.service;

import com.limitactivitysystem.activity.dto.CreateActivitySessionRequest;
import com.limitactivitysystem.activity.exception.ActivityErrorCode;
import com.limitactivitysystem.activity.exception.ActivityException;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.cache.CacheInvalidationRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublisherActivitySessionService {

    private final ActivityOwnershipService ownershipService;
    private final ActivitySessionMapper activitySessionMapper;
    private final ActivitySessionConfigurationValidator configurationValidator;
    private final CacheInvalidationRecorder invalidations;

    public PublisherActivitySessionService(ActivityOwnershipService ownershipService,
                                           ActivitySessionMapper activitySessionMapper,
                                           ActivitySessionConfigurationValidator configurationValidator,
                                           CacheInvalidationRecorder invalidations) {
        this.ownershipService = ownershipService;
        this.activitySessionMapper = activitySessionMapper;
        this.configurationValidator = configurationValidator;
        this.invalidations = invalidations;
    }

    @Transactional
    public ActivitySession createSession(Long activityId, CreateActivitySessionRequest request) {
        Activity activity = ownershipService.assertActivityOwner(activityId);
        if (!"DRAFT".equals(activity.getStatus())) {
            throw new ActivityException(ActivityErrorCode.ACTIVITY_NOT_DRAFT,
                    "Only DRAFT activities allow session configuration");
        }

        ActivitySession session = new ActivitySession();
        session.setActivityId(activity.getActivityId());
        session.setStartTime(request.getStartTime());
        session.setEndTime(request.getEndTime());
        session.setCapacity(request.getCapacity());
        session.setMaxTicketsPerUser(request.getMaxTicketsPerUser());
        session.setSeatMode(request.getSeatMode());
        session.setStatus("SCHEDULED");
        configurationValidator.validate(activity, session);
        activitySessionMapper.insert(session);
        invalidations.record("las:seat-layout:" + session.getActivitySessionId());
        return session;
    }
}
