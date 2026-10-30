package com.limitactivitysystem.refund.service;

import com.limitactivitysystem.refund.dto.*;
import com.limitactivitysystem.refund.exception.TicketRefundException;
import com.limitactivitysystem.security.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.util.HashSet;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import static com.limitactivitysystem.refund.exception.TicketRefundErrorCode.*;

@Service
@Validated
public class TicketRefundService {
    private final CurrentUserProvider currentUser;
    private final Clock clock;
    private final TicketRefundApplicationService applications;
    public TicketRefundService(CurrentUserProvider currentUser, @Qualifier("ticketRefundClock") Clock clock,
                               TicketRefundApplicationService applications) {
        this.currentUser = currentUser; this.clock = clock; this.applications = applications;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TicketRefundResponse submit(@NotNull @Valid CreateTicketRefundRequest request) {
        if (new HashSet<>(request.getTicketIds()).size() != request.getTicketIds().size()) {
            throw new TicketRefundException(INVALID_REFUND_REQUEST, "Duplicate ticketIds are not allowed");
        }
        Long buyerId = currentUser.getCurrentUserId();
        var requestedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        try { return applications.apply(buyerId, request, requestedAt); }
        catch (DuplicateKeyException conflict) {
            String message = String.valueOf(conflict.getMostSpecificCause().getMessage());
            if (message.contains("uq_refund_request")) {
                // The failed transaction has rolled back before a fresh transaction reads the winner.
                return applications.apply(buyerId, request, requestedAt);
            }
            if (message.contains("uq_refund_ticket_claim")) {
                throw new TicketRefundException(REFUND_ALREADY_REQUESTED, "Ticket already has an accepted or successful refund");
            }
            throw conflict;
        }
    }
}
