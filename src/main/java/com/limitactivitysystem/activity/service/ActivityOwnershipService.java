package com.limitactivitysystem.activity.service;

import com.limitactivitysystem.activity.exception.ActivityErrorCode;
import com.limitactivitysystem.activity.exception.ActivityException;
import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.mapper.SessionSeatMapper;
import com.limitactivitysystem.activity.mapper.SessionZoneMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionSeat;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.CurrentUserProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves stored parent relations and checks ownership against the current active publisher.
 * Locks the Activity root for publisher configuration and whitelist management. A caller that
 * mutates resources must keep the check and mutation in one surrounding transaction, so the
 * root lock is held until that transaction ends. Lifecycle rules such as DRAFT remain with callers.
 */
@Service
@Transactional
public class ActivityOwnershipService {

    private final CurrentUserProvider currentUserProvider;
    private final ActivityMapper activityMapper;
    private final ActivitySessionMapper sessionMapper;
    private final SessionZoneMapper zoneMapper;
    private final SessionSeatMapper seatMapper;

    public ActivityOwnershipService(CurrentUserProvider currentUserProvider, ActivityMapper activityMapper,
                                    ActivitySessionMapper sessionMapper, SessionZoneMapper zoneMapper,
                                    SessionSeatMapper seatMapper) {
        this.currentUserProvider = currentUserProvider;
        this.activityMapper = activityMapper;
        this.sessionMapper = sessionMapper;
        this.zoneMapper = zoneMapper;
        this.seatMapper = seatMapper;
    }

    public Activity assertActivityOwner(Long activityId) {
        return lockOwnedActivity(activityId, currentPublisherId());
    }

    public SessionOwnership assertSessionOwner(Long sessionId) {
        return resolveSession(sessionId, currentPublisherId());
    }

    public ZoneOwnership assertZoneOwner(Long zoneId) {
        return resolveZone(zoneId, currentPublisherId());
    }

    public SeatOwnership assertSeatOwner(Long seatId) {
        Long publisherId = currentPublisherId();
        SessionSeat seat = seatMapper.findById(seatId);
        if (seat == null) {
            throw new ActivityException(ActivityErrorCode.SEAT_NOT_FOUND, "Seat not found");
        }
        ZoneOwnership parent = resolveZone(seat.getZoneId(), publisherId);
        return new SeatOwnership(parent.activity(), parent.session(), parent.zone(), seat);
    }

    private SessionOwnership resolveSession(Long sessionId, Long publisherId) {
        ActivitySession session = sessionMapper.findById(sessionId);
        if (session == null) {
            throw new ActivityException(ActivityErrorCode.SESSION_NOT_FOUND, "Session not found");
        }
        Activity activity = lockOwnedActivity(session.getActivityId(), publisherId);
        return new SessionOwnership(activity, session);
    }

    private ZoneOwnership resolveZone(Long zoneId, Long publisherId) {
        SessionZone zone = zoneMapper.findById(zoneId);
        if (zone == null) {
            throw new ActivityException(ActivityErrorCode.ZONE_NOT_FOUND, "Zone not found");
        }
        SessionOwnership parent = resolveSession(zone.getSessionId(), publisherId);
        return new ZoneOwnership(parent.activity(), parent.session(), zone);
    }

    private Activity lockOwnedActivity(Long activityId, Long publisherId) {
        // Child lookups above do not lock child rows. The Activity remains the first write lock.
        Activity activity = activityMapper.findByIdForUpdate(activityId);
        if (activity == null) {
            throw new ActivityException(ActivityErrorCode.ACTIVITY_NOT_FOUND, "Activity not found");
        }
        if (!publisherId.equals(activity.getPublisherId())) {
            throw new ActivityException(ActivityErrorCode.NOT_ACTIVITY_OWNER,
                    "Only the activity owner can manage this resource");
        }
        return activity;
    }

    private Long currentPublisherId() {
        AppUser publisher = currentUserProvider.getCurrentActiveUser();
        if (!"PUBLISHER".equals(publisher.getRole())) {
            throw new AccessDeniedException("Insufficient permission");
        }
        return publisher.getId();
    }

    public record SessionOwnership(Activity activity, ActivitySession session) {
    }

    public record ZoneOwnership(Activity activity, ActivitySession session, SessionZone zone) {
    }

    public record SeatOwnership(Activity activity, ActivitySession session, SessionZone zone, SessionSeat seat) {
    }
}
