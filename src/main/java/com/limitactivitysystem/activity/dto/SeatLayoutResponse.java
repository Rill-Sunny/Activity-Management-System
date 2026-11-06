package com.limitactivitysystem.activity.dto;

import java.util.List;

/** Static seat positions only; seat availability is intentionally never cached here. */
public record SeatLayoutResponse(Long sessionId, List<ZoneLayout> zones) {
    public SeatLayoutResponse {
        zones = List.copyOf(zones);
    }

    public record ZoneLayout(Long zoneId, String name, String zoneType, List<SeatPosition> seats) {
        public ZoneLayout {
            seats = List.copyOf(seats);
        }
    }

    public record SeatPosition(Long seatId, Integer rowNo, Integer seatNo) { }
}
