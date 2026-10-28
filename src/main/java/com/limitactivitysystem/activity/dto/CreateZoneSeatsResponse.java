package com.limitactivitysystem.activity.dto;

import java.util.List;

public record CreateZoneSeatsResponse(int createdCount, List<Long> seatIds) {
}
