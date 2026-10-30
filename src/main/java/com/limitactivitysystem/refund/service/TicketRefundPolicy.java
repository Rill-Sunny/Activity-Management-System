package com.limitactivitysystem.refund.service;

import com.limitactivitysystem.refund.model.TicketRefundDecision;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface TicketRefundPolicy {
    TicketRefundDecision evaluate(LocalDateTime requestedAt, LocalDateTime activityOpenTime,
                                  LocalDateTime sessionStartTime, BigDecimal paidPrice);
}
