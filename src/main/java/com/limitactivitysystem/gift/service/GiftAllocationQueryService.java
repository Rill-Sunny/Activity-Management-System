package com.limitactivitysystem.gift.service;

import com.limitactivitysystem.gift.dto.*;
import com.limitactivitysystem.gift.exception.GiftException;
import com.limitactivitysystem.gift.mapper.GiftOperationMapper;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;
import static com.limitactivitysystem.gift.exception.GiftErrorCode.*;

@Service
@Validated
public class GiftAllocationQueryService {
    private final GiftOwnershipService ownership;
    private final GiftOperationMapper operations;
    private final GiftOperationReader reader;
    public GiftAllocationQueryService(GiftOwnershipService ownership, GiftOperationMapper operations, GiftOperationReader reader) {
        this.ownership = ownership; this.operations = operations; this.reader = reader;
    }

    /** Shared scope locks coordinate permission changes and concurrent allocations, without resource writes. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public GiftAllocationPage list(@NotNull @Positive Long zoneId, @Min(1) @Max(100) int limit, @PositiveOrZero long afterOperationId) {
        var scope = ownership.readOwnedGiftZone(zoneId);
        var rows = operations.findByZoneAfter(zoneId, afterOperationId, limit + 1);
        var page = rows.stream().limit(limit).map(saved -> {
            if (!scope.session().getActivitySessionId().equals(saved.getSessionId())) { throw new GiftException(GIFT_SCOPE_INVALID, "Operation session does not match its Zone"); }
            return reader.response(saved);
        }).toList();
        Long next = rows.size() > limit ? page.get(page.size() - 1).operationId() : null;
        return new GiftAllocationPage(page, next);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public GiftAllocationResponse get(@NotNull @Positive Long operationId) {
        ownership.requirePublisher();
        // Discovery does not lock the operation before its parent resources.
        var discovered = operations.findById(operationId);
        if (discovered == null) { throw new GiftException(GIFT_OPERATION_NOT_FOUND, "GIFT allocation not found"); }
        var scope = ownership.readOwnedGiftZone(discovered.getZoneId());
        var saved = operations.findById(operationId);
        if (saved == null) { throw new GiftException(GIFT_OPERATION_NOT_FOUND, "GIFT allocation not found"); }
        if (!scope.zone().getZoneId().equals(saved.getZoneId()) || !scope.session().getActivitySessionId().equals(saved.getSessionId())) {
            throw new GiftException(GIFT_SCOPE_INVALID, "GIFT allocation relationships changed");
        }
        return reader.response(saved);
    }
}
