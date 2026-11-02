package com.limitactivitysystem.gift.dto;

import java.time.LocalDateTime;
import java.util.List;

public record GiftAllocationResponse(Long operationId, String requestId, Long publisherId, Long sessionId,
        Long zoneId, String operationType, Integer quantity, String status, LocalDateTime createdAt, List<Long> seatIds) { }
