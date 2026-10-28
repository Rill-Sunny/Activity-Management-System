package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.ticketorder.dto.CreateTicketOrderRequest;
import com.limitactivitysystem.ticketorder.dto.TicketOrderResponse;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.whitelist.exception.QualificationDeniedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class TicketOrderService {
    private final TicketOrderCreationService creation;
    private final TicketOrderReplayService replay;

    public TicketOrderService(TicketOrderCreationService creation, TicketOrderReplayService replay) {
        this.creation = creation;
        this.replay = replay;
    }

    /** Coordinates separate transactions so failed INSERTs cannot contaminate replay reads or caller snapshots. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CreationResult createOrder(@NotNull @Valid CreateTicketOrderRequest request) {
        TicketOrderResponse existing = replay.findIfPresent(request);
        if (existing != null) { return new CreationResult(existing, false); }
        try {
            return new CreationResult(creation.createOrder(request), true);
        } catch (DuplicateKeyException | TicketOrderException | QualificationDeniedException exception) {
            // The creation proxy has already rolled back and released its locks before this read starts.
            // A concurrent winner may also commit while a late attempt fails a creation-only precheck.
            existing = replay.findIfPresent(request);
            if (existing != null) { return new CreationResult(existing, false); }
            throw exception;
        }
    }

    public record CreationResult(TicketOrderResponse order, boolean created) { }
}
