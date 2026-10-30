package com.limitactivitysystem.reservation.dto;

import java.time.LocalDateTime;

public record ReservationResponse(Long reservationId, Long userId, Long sessionId, String status,
                                  LocalDateTime createdAt, LocalDateTime cancelledAt) { }
