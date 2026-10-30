package com.limitactivitysystem.refund.service;

import com.limitactivitysystem.refund.exception.TicketRefundErrorCode;
import com.limitactivitysystem.refund.exception.TicketRefundException;
import com.limitactivitysystem.refund.model.TicketRefundDecision;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import static com.limitactivitysystem.refund.exception.TicketRefundErrorCode.*;

@Component
public class OrdinaryTicketRefundPolicy implements TicketRefundPolicy {
    private static final BigDecimal NO_FEE = new BigDecimal("0.0000");
    private static final BigDecimal FIFTEEN_PERCENT = new BigDecimal("0.1500");
    private static final BigDecimal FORTY_PERCENT = new BigDecimal("0.4000");

    @Override
    public TicketRefundDecision evaluate(LocalDateTime requestedAt, LocalDateTime openTime,
                                          LocalDateTime startTime, BigDecimal paidPrice) {
        if (requestedAt == null || openTime == null || startTime == null) {
            throw new IllegalArgumentException("Refund policy requires application, opening and start times");
        }
        if (paidPrice == null || paidPrice.signum() <= 0 || paidPrice.stripTrailingZeros().scale() > 2) {
            throw new TicketRefundException(REFUND_AMOUNT_CALCULATION_ERROR,
                    "Stored ticket price cannot produce a valid refund amount");
        }
        // Priority 0: even the opening promotion cannot override a session that has started.
        if (!requestedAt.isBefore(startTime)) {
            return denied(REFUND_SESSION_STARTED);
        }
        // Priority 1: the 48 hours belong to the activity opening, not the payment.
        if (!requestedAt.isBefore(openTime) && requestedAt.isBefore(openTime.plusHours(48))) {
            return allowed(paidPrice, NO_FEE);
        }
        // Priorities 2–5: each earlier branch has already excluded the preceding range.
        if (requestedAt.isBefore(startTime.minusDays(30))) { return allowed(paidPrice, NO_FEE); }
        if (requestedAt.isBefore(startTime.minusDays(14))) { return allowed(paidPrice, FIFTEEN_PERCENT); }
        if (requestedAt.isBefore(startTime.minusDays(10))) { return allowed(paidPrice, FORTY_PERCENT); }
        return denied(REFUND_WINDOW_CLOSED);
    }

    private TicketRefundDecision allowed(BigDecimal price, BigDecimal feeRate) {
        BigDecimal refundRate = BigDecimal.ONE.subtract(feeRate);
        BigDecimal refundAmount = price.multiply(refundRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal feeAmount = price.subtract(refundAmount).setScale(2, RoundingMode.HALF_UP);
        return new TicketRefundDecision(true, feeRate, refundRate, feeAmount, refundAmount, null);
    }

    private TicketRefundDecision denied(TicketRefundErrorCode reason) {
        return new TicketRefundDecision(false, null, null, null, null, reason);
    }
}
