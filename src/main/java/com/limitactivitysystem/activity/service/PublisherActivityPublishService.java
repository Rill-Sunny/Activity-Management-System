package com.limitactivitysystem.activity.service;

import com.limitactivitysystem.activity.exception.ActivityErrorCode;
import com.limitactivitysystem.activity.exception.ActivityException;
import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.mapper.SessionSeatMapper;
import com.limitactivitysystem.activity.mapper.SessionZoneMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionZone;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PublisherActivityPublishService {

    private final ActivityMapper activityMapper;
    private final ActivitySessionMapper sessionMapper;
    private final SessionZoneMapper zoneMapper;
    private final SessionSeatMapper seatMapper;
    private final ActivityOwnershipService ownershipService;
    private final ActivitySessionConfigurationValidator sessionConfigurationValidator;

    public PublisherActivityPublishService(ActivityMapper activityMapper, ActivitySessionMapper sessionMapper,
                                           SessionZoneMapper zoneMapper, SessionSeatMapper seatMapper,
                                           ActivityOwnershipService ownershipService,
                                           ActivitySessionConfigurationValidator sessionConfigurationValidator) {
        this.activityMapper = activityMapper;
        this.sessionMapper = sessionMapper;
        this.zoneMapper = zoneMapper;
        this.seatMapper = seatMapper;
        this.ownershipService = ownershipService;
        this.sessionConfigurationValidator = sessionConfigurationValidator;
    }

    // Account lookup may precede a wait for the root lock. READ_COMMITTED prevents that earlier
    // consistent read from fixing an old snapshot for the subsequent session configuration query.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void publishActivity(Long activityId) {
        Activity activity = ownershipService.assertActivityOwner(activityId);
        if ("PUBLISHED".equals(activity.getStatus())) {
            throw new ActivityException(ActivityErrorCode.ACTIVITY_ALREADY_PUBLISHED, "Activity is already published");
        }
        if (!"DRAFT".equals(activity.getStatus())) {
            throw new ActivityException(ActivityErrorCode.ACTIVITY_NOT_DRAFT, "Only DRAFT activities can be published");
        }
        if (activity.getOpenTime() == null || activity.getCloseTime() == null
                || !activity.getOpenTime().isBefore(activity.getCloseTime())) {
            throw new ActivityException(ActivityErrorCode.INVALID_ACTIVITY_WINDOW,
                    "Activity openTime must be earlier than closeTime");
        }
        if (!"FREE".equals(activity.getType()) && !"PAID".equals(activity.getType())) {
            throw new ActivityException(ActivityErrorCode.INVALID_ACTIVITY_TYPE, "Activity type must be FREE or PAID");
        }

        // Every configuration writer locks this same activity row before inserting child records.
        List<ActivitySession> sessions = sessionMapper.findByActivityId(activityId);
        if (sessions == null || sessions.isEmpty()) {
            throw new ActivityException(ActivityErrorCode.INVALID_SESSION_CONFIG,
                    "Activity must have at least one session before publication");
        }
        LocalDateTime earliestStartTime = null;
        for (ActivitySession session : sessions) {
            sessionConfigurationValidator.validate(activity, session);
            if (earliestStartTime == null || session.getStartTime().isBefore(earliestStartTime)) {
                earliestStartTime = session.getStartTime();
            }
        }
        if (activity.getCloseTime().isAfter(earliestStartTime)) {
            throw new ActivityException(ActivityErrorCode.INVALID_ACTIVITY_WINDOW,
                    "Activity closeTime must not be later than the earliest session startTime");
        }

        if ("PAID".equals(activity.getType())) {
            for (ActivitySession session : sessions) {
                validatePaidSessionResources(session);
            }
        }

        // Keep the status write last. Any validation or write failure rolls back this transaction.
        if (activityMapper.updateStatus(activityId, "PUBLISHED") != 1) {
            throw new ActivityException(ActivityErrorCode.ACTIVITY_PUBLISH_CONFLICT,
                    "Activity state changed; publication failed");
        }
    }

    private void validatePaidSessionResources(ActivitySession session) {
        List<SessionZone> zones = zoneMapper.findBySessionId(session.getActivitySessionId());
        // Include both PAID and GIFT zones, using long so summed INT capacities cannot wrap.
        long plannedCapacity = zones.stream().mapToLong(zone -> zone.getTotalAmount().longValue()).sum();
        if (plannedCapacity > session.getCapacity()) {
            throw new ActivityException(ActivityErrorCode.ZONE_CAPACITY_EXCEEDED,
                    "Session " + session.getActivitySessionId()
                            + " PAID and GIFT zone totalAmount (" + plannedCapacity
                            + ") must not exceed capacity (" + session.getCapacity() + ")");
        }

        for (SessionZone zone : zones) {
            long seatCount = seatMapper.countByZoneId(zone.getZoneId());
            if (seatCount != zone.getTotalAmount().longValue()) {
                throw new ActivityException(ActivityErrorCode.SEAT_COUNT_MISMATCH,
                        "Zone " + zone.getZoneId() + " seat count (" + seatCount
                                + ") must equal totalAmount (" + zone.getTotalAmount() + ")");
            }
        }
    }
}
