package com.limitactivitysystem.gift.service;

import com.limitactivitysystem.gift.dto.AllocateGiftSeatsRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class GiftAllocationService {
    private final GiftAllocationTransactionService transactions;
    public GiftAllocationService(GiftAllocationTransactionService transactions) { this.transactions = transactions; }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public GiftAllocationTransactionService.Result allocate(@NotNull @Positive Long zoneId, @NotNull @Valid AllocateGiftSeatsRequest request) {
        try { return transactions.allocate(zoneId, request); }
        catch (DuplicateKeyException conflict) {
            if (!String.valueOf(conflict.getMostSpecificCause().getMessage()).contains("uq_gift_operation_request")) { throw conflict; }
            return transactions.replay(zoneId, request);
        }
    }
}
