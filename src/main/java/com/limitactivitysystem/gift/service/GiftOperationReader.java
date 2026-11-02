package com.limitactivitysystem.gift.service;

import com.limitactivitysystem.gift.dto.GiftAllocationResponse;
import com.limitactivitysystem.gift.exception.GiftException;
import com.limitactivitysystem.gift.mapper.GiftOperationMapper;
import com.limitactivitysystem.gift.model.GiftInventoryOperation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.limitactivitysystem.gift.exception.GiftErrorCode.GIFT_RESOURCE_CONFLICT;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class GiftOperationReader {
    private final GiftOperationMapper operations;
    public GiftOperationReader(GiftOperationMapper operations) { this.operations = operations; }
    public GiftAllocationResponse response(GiftInventoryOperation saved) {
        if (saved == null || !"SUCCEEDED".equals(saved.getStatus())) { throw new GiftException(GIFT_RESOURCE_CONFLICT, "Stored GIFT result is incomplete"); }
        var seats = operations.findSeatIds(saved.getOperationId());
        if (seats.size() != saved.getQuantity()) { throw new GiftException(GIFT_RESOURCE_CONFLICT, "GIFT operation does not have the expected Seat details"); }
        return new GiftAllocationResponse(saved.getOperationId(), saved.getRequestId(), saved.getPublisherId(), saved.getSessionId(),
                saved.getZoneId(), saved.getOperationType(), saved.getQuantity(), saved.getStatus(), saved.getCreatedAt(), seats);
    }
}
