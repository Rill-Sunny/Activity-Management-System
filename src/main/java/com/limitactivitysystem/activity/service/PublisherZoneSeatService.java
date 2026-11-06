package com.limitactivitysystem.activity.service;

import com.limitactivitysystem.activity.dto.CreateZoneSeatsRequest;
import com.limitactivitysystem.activity.exception.ActivityErrorCode;
import com.limitactivitysystem.activity.exception.ActivityException;
import com.limitactivitysystem.activity.mapper.SessionSeatMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.SessionSeat;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.cache.CacheInvalidationRecorder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class PublisherZoneSeatService {

    private final ActivityOwnershipService ownershipService;
    private final SessionSeatMapper seatMapper;
    private final CacheInvalidationRecorder invalidations;

    public PublisherZoneSeatService(ActivityOwnershipService ownershipService, SessionSeatMapper seatMapper,
                                    CacheInvalidationRecorder invalidations) {
        this.ownershipService = ownershipService;
        this.seatMapper = seatMapper;
        this.invalidations = invalidations;
    }

    @Transactional
    public List<SessionSeat> createSeats(Long zoneId, CreateZoneSeatsRequest request) {
        ActivityOwnershipService.ZoneOwnership ownership = ownershipService.assertZoneOwner(zoneId);
        SessionZone zone = ownership.zone();
        Activity activity = ownership.activity();
        if (!"DRAFT".equals(activity.getStatus())) {
            throw new ActivityException(ActivityErrorCode.ACTIVITY_NOT_DRAFT,
                    "Only DRAFT activities allow seat configuration");
        }
        if (!"PAID".equals(activity.getType())) {
            throw new ActivityException(ActivityErrorCode.INVALID_SEAT_CONFIG, "Only PAID activities allow zone seats");
        }

        List<SessionSeat> seats = new ArrayList<>(request.getSeats().size());
        Set<SeatPosition> positions = new HashSet<>();
        for (CreateZoneSeatsRequest.SeatDefinition definition : request.getSeats()) {
            if (!positions.add(new SeatPosition(definition.getRowNo(), definition.getSeatNo()))) {
                throw new ActivityException(ActivityErrorCode.INVALID_SEAT_CONFIG, "Duplicate seat position in request");
            }
            SessionSeat seat = new SessionSeat();
            seat.setZoneId(zone.getZoneId());
            seat.setRowNo(definition.getRowNo());
            seat.setSeatNo(definition.getSeatNo());
            seat.setAllocationPriority(definition.getAllocationPriority());
            seat.setAllocationStatus("AVAILABLE");
            seats.add(seat);
        }
        try {
            seatMapper.batchInsert(seats);
        } catch (DuplicateKeyException exception) {
            throw new ActivityException(ActivityErrorCode.SEAT_ALREADY_EXISTS, "Seat position already exists in this zone");
        }
        invalidations.record("las:seat-layout:" + ownership.session().getActivitySessionId());
        return seats;
    }

    private record SeatPosition(Integer rowNo, Integer seatNo) {
    }
}
