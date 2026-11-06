package com.limitactivitysystem.activity.service;

import com.limitactivitysystem.activity.dto.CreateSessionZoneRequest;
import com.limitactivitysystem.activity.exception.ActivityErrorCode;
import com.limitactivitysystem.activity.exception.ActivityException;
import com.limitactivitysystem.activity.mapper.SessionZoneMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.cache.CacheInvalidationRecorder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublisherSessionZoneService {

    private final ActivityOwnershipService ownershipService;
    private final SessionZoneMapper zoneMapper;
    private final CacheInvalidationRecorder invalidations;

    public PublisherSessionZoneService(ActivityOwnershipService ownershipService, SessionZoneMapper zoneMapper,
                                       CacheInvalidationRecorder invalidations) {
        this.ownershipService = ownershipService;
        this.zoneMapper = zoneMapper;
        this.invalidations = invalidations;
    }

    @Transactional
    public SessionZone createZone(Long sessionId, CreateSessionZoneRequest request) {
        ActivityOwnershipService.SessionOwnership ownership = ownershipService.assertSessionOwner(sessionId);
        ActivitySession session = ownership.session();
        Activity activity = ownership.activity();
        if (!"DRAFT".equals(activity.getStatus())) {
            throw new ActivityException(ActivityErrorCode.ACTIVITY_NOT_DRAFT,
                    "Only DRAFT activities allow zone configuration");
        }
        if (!"PAID".equals(activity.getType())) {
            throw new ActivityException(ActivityErrorCode.INVALID_ZONE_CONFIG, "Only PAID activities allow zones");
        }

        if ("PAID".equals(request.getZoneType())) {
            if (request.getPrice().signum() <= 0) {
                throw new ActivityException(ActivityErrorCode.INVALID_ZONE_CONFIG, "PAID zone price must be positive");
            }
        } else if ("GIFT".equals(request.getZoneType())) {
            if (request.getPrice().signum() != 0) {
                throw new ActivityException(ActivityErrorCode.INVALID_ZONE_CONFIG, "GIFT zone price must be zero");
            }
        } else {
            throw new ActivityException(ActivityErrorCode.INVALID_ZONE_CONFIG, "Zone type must be PAID or GIFT");
        }

        SessionZone zone = new SessionZone();
        zone.setSessionId(session.getActivitySessionId());
        zone.setName(request.getName());
        zone.setZoneType(request.getZoneType());
        zone.setPrice(request.getPrice());
        zone.setTotalAmount(request.getTotalAmount());
        zone.setRemainsAmount(request.getTotalAmount());
        try {
            zoneMapper.insert(zone);
        } catch (DuplicateKeyException exception) {
            throw new ActivityException(ActivityErrorCode.ZONE_ALREADY_EXISTS, "Zone name already exists in this session");
        }
        invalidations.record("las:seat-layout:" + session.getActivitySessionId());
        return zone;
    }
}
