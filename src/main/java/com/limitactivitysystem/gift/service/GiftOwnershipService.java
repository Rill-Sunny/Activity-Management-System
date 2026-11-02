package com.limitactivitysystem.gift.service;

import com.limitactivitysystem.activity.mapper.*;
import com.limitactivitysystem.activity.model.*;
import com.limitactivitysystem.gift.exception.GiftException;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.limitactivitysystem.gift.exception.GiftErrorCode.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class GiftOwnershipService {
    private final CurrentUserProvider currentUser;
    private final AppUserMapper users;
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final SessionZoneMapper zones;

    public GiftOwnershipService(CurrentUserProvider currentUser, AppUserMapper users, ActivityMapper activities,
            ActivitySessionMapper sessions, SessionZoneMapper zones) {
        this.currentUser = currentUser; this.users = users; this.activities = activities; this.sessions = sessions; this.zones = zones;
    }

    public Long requirePublisher() {
        Long id = currentUser.getAuthenticatedUserId(); var user = users.findByIdForShare(id);
        if (user == null || !"ACTIVE".equals(user.getStatus())) { throw new GiftException(GIFT_USER_DISABLED, "Current account is not active"); }
        if (!"PUBLISHER".equals(user.getRole())) { throw new GiftException(GIFT_ACCESS_DENIED, "Publisher role is required"); }
        return id;
    }
    public Scope lockOwnedGiftZone(Long zoneId) { return resolve(requirePublisher(), zoneId, true); }
    public Scope readOwnedGiftZone(Long zoneId) { return resolve(requirePublisher(), zoneId, false); }

    private Scope resolve(Long publisherId, Long zoneId, boolean writeZone) {
        var preliminary = zones.findById(zoneId);
        if (preliminary == null) { throw new GiftException(GIFT_ZONE_NOT_FOUND, "Zone not found"); }
        var parent = sessions.findById(preliminary.getSessionId());
        if (parent == null) { throw new GiftException(GIFT_SCOPE_INVALID, "Zone session is missing"); }
        var activity = activities.findByIdForShare(parent.getActivityId());
        if (activity == null) { throw new GiftException(GIFT_SCOPE_INVALID, "Session activity is missing"); }
        if (!publisherId.equals(activity.getPublisherId())) { throw new GiftException(GIFT_ACCESS_DENIED, "Only the activity owner can manage GIFT resources"); }
        var session = sessions.findByIdForShare(parent.getActivitySessionId());
        var zone = writeZone ? zones.findByIdForUpdate(zoneId) : zones.findByIdForShare(zoneId);
        if (zone == null) { throw new GiftException(GIFT_ZONE_NOT_FOUND, "Zone not found"); }
        if (session == null || !activity.getActivityId().equals(session.getActivityId()) || !session.getActivitySessionId().equals(zone.getSessionId())) {
            throw new GiftException(GIFT_SCOPE_INVALID, "GIFT resource relationships changed");
        }
        if (!"GIFT".equals(zone.getZoneType())) { throw new GiftException(GIFT_ZONE_NOT_GIFT, "A GIFT zone is required"); }
        return new Scope(activity, session, zone);
    }
    public record Scope(Activity activity, ActivitySession session, SessionZone zone) { }
}
