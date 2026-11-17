package com.limitactivitysystem.qualification.model;

import java.time.LocalDateTime;

public record TicketQualificationChangedEvent(
        String eventId,
        Long buyerId,
        Long activityId,
        Long sessionId,
        Long refundId,
        LocalDateTime occurredAt,
        Long eventVersion) { }
