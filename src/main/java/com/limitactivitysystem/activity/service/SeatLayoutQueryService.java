package com.limitactivitysystem.activity.service;

import com.limitactivitysystem.activity.dto.SeatLayoutResponse;
import com.limitactivitysystem.activity.exception.ActivityErrorCode;
import com.limitactivitysystem.activity.exception.ActivityException;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.mapper.SessionSeatMapper;
import com.limitactivitysystem.activity.mapper.SessionZoneMapper;
import com.limitactivitysystem.cache.CacheAsideService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SeatLayoutQueryService {
    private final ActivitySessionMapper sessions;
    private final SessionZoneMapper zones;
    private final SessionSeatMapper seats;
    private final CacheAsideService cache;

    public SeatLayoutQueryService(ActivitySessionMapper sessions, SessionZoneMapper zones,
                                  SessionSeatMapper seats, CacheAsideService cache) {
        this.sessions = sessions;
        this.zones = zones;
        this.seats = seats;
        this.cache = cache;
    }

    public SeatLayoutResponse get(Long sessionId) {
        SeatLayoutResponse layout = cache.get("las:seat-layout:" + sessionId, SeatLayoutResponse.class,
                () -> load(sessionId));
        if (layout == null) {
            throw new ActivityException(ActivityErrorCode.SESSION_NOT_FOUND, "Session not found");
        }
        return layout;
    }

    private SeatLayoutResponse load(Long sessionId) {
        if (sessions.findById(sessionId) == null) return null;
        List<SeatLayoutResponse.ZoneLayout> layouts = zones.findBySessionId(sessionId).stream().map(zone -> {
            List<SeatLayoutResponse.SeatPosition> positions = seats.findByZoneId(zone.getZoneId()).stream()
                    .map(seat -> new SeatLayoutResponse.SeatPosition(seat.getSeatId(), seat.getRowNo(), seat.getSeatNo()))
                    .toList();
            return new SeatLayoutResponse.ZoneLayout(zone.getZoneId(), zone.getName(), zone.getZoneType(), positions);
        }).toList();
        return new SeatLayoutResponse(sessionId, layouts);
    }
}
