package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.activity.mapper.SessionZoneMapper;
import com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.mapper.UserSessionPurchaseMapper;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class TicketPaymentResourceService {
    private final UserSessionPurchaseMapper purchases;
    private final SessionZoneMapper zones;

    public TicketPaymentResourceService(UserSessionPurchaseMapper purchases, SessionZoneMapper zones) {
        this.purchases = purchases;
        this.zones = zones;
    }

    // Caller supplies the trusted buyer/session and the frozen limit read in the payment transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    public void occupyPurchaseQuota(@NotNull @Positive Long userId, @NotNull @Positive Long sessionId,
                                    @Positive int quantity, @Positive int purchaseLimit) {
        purchases.ensureExists(userId, sessionId);
        if (purchases.tryIncrease(userId, sessionId, quantity, purchaseLimit) != 1) {
            throw new TicketOrderException(TicketOrderErrorCode.PURCHASE_LIMIT_EXCEEDED,
                    "Current valid ticket count would exceed the session purchase limit");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void decreaseStock(@NotNull @Positive Long zoneId, @Positive int quantity) {
        if (zones.decreaseStock(zoneId, quantity) != 1) {
            throw new TicketOrderException(TicketOrderErrorCode.INSUFFICIENT_INVENTORY,
                    "Paid zone inventory could not be acquired");
        }
    }
}
