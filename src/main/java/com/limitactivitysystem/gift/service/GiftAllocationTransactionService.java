package com.limitactivitysystem.gift.service;

import com.limitactivitysystem.activity.model.*;
import com.limitactivitysystem.gift.dto.*;
import com.limitactivitysystem.gift.exception.GiftException;
import com.limitactivitysystem.gift.mapper.*;
import com.limitactivitysystem.gift.model.GiftInventoryOperation;
import com.limitactivitysystem.gift.service.GiftOwnershipService.Scope;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.Comparator;
import static com.limitactivitysystem.gift.exception.GiftErrorCode.*;

@Service
@Validated
public class GiftAllocationTransactionService {
    private final GiftOwnershipService ownership;
    private final GiftOperationReader reader;
    private final GiftInventoryMapper inventory;
    private final GiftOperationMapper operations;
    private final DatabaseTimeMapper time;

    public GiftAllocationTransactionService(GiftOwnershipService ownership, GiftOperationReader reader,
            GiftInventoryMapper inventory, GiftOperationMapper operations, DatabaseTimeMapper time) {
        this.ownership = ownership; this.reader = reader; this.inventory = inventory; this.operations = operations; this.time = time;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Result allocate(@NotNull @Positive Long zoneId, @NotNull @Valid AllocateGiftSeatsRequest request) {
        return execute(zoneId, request, false);
    }

    /** Invoked only after the losing transaction has finished rollback. Never allocates new resources. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Result replay(@NotNull @Positive Long zoneId, @NotNull @Valid AllocateGiftSeatsRequest request) {
        return execute(zoneId, request, true);
    }

    private Result execute(Long zoneId, AllocateGiftSeatsRequest request, boolean replayOnly) {
        var scope = ownership.lockOwnedGiftZone(zoneId);
        var saved = operations.findByRequest(scope.activity().getPublisherId(), request.getRequestId());
        if (saved != null) {
            if (!zoneId.equals(saved.getZoneId()) || !scope.session().getActivitySessionId().equals(saved.getSessionId())
                    || !request.getQuantity().equals(saved.getQuantity()) || !"ALLOCATE".equals(saved.getOperationType())) {
                throw new GiftException(GIFT_IDEMPOTENCY_CONFLICT, "requestId is already used for different GIFT allocation content");
            }
            return new Result(reader.response(saved), false);
        }
        if (replayOnly) { throw new GiftException(GIFT_IDEMPOTENCY_CONFLICT, "Committed GIFT operation could not be found"); }
        validateLifecycle(scope, time.currentTime());
        if (scope.zone().getRemainsAmount() < request.getQuantity()) {
            throw new GiftException(GIFT_INSUFFICIENT_INVENTORY, "GIFT zone has insufficient inventory");
        }
        var selected = inventory.findAvailableSeats(zoneId, request.getQuantity());
        require(selected.size() == request.getQuantity() && selected.stream().map(SessionSeat::getSeatId).distinct().count() == selected.size(),
                "GIFT stock does not match available seats");
        require(selected.stream().allMatch(seat -> zoneId.equals(seat.getZoneId()) && "AVAILABLE".equals(seat.getAllocationStatus())),
                "Candidate seat does not belong to the current GIFT pool");
        var ordered = selected.stream().sorted(Comparator.comparing(SessionSeat::getSeatId)).toList();
        for (var seat : ordered) { changed(inventory.occupySeat(seat.getSeatId(), zoneId), "GIFT seat could not be occupied"); }
        changed(inventory.decreaseStock(zoneId, scope.session().getActivitySessionId(), request.getQuantity()), "GIFT stock could not be decreased");
        var operation = new GiftInventoryOperation();
        operation.setPublisherId(scope.activity().getPublisherId()); operation.setRequestId(request.getRequestId());
        operation.setSessionId(scope.session().getActivitySessionId()); operation.setZoneId(zoneId); operation.setQuantity(request.getQuantity());
        changed(operations.insert(operation), "GIFT operation could not be saved");
        for (var seat : ordered) { changed(operations.insertItem(operation.getOperationId(), seat.getSeatId()), "GIFT operation item could not be saved"); }
        // Resource/history SQL may wait. New allocations must still be legal after those waits.
        validateLifecycle(scope, time.currentTime());
        return new Result(reader.response(operations.findById(operation.getOperationId())), true);
    }

    private void validateLifecycle(Scope scope, LocalDateTime now) {
        if (!"PAID".equals(scope.activity().getType()) || !"PUBLISHED".equals(scope.activity().getStatus())
                || !"SCHEDULED".equals(scope.session().getStatus()) || scope.session().getStartTime() == null
                || !now.isBefore(scope.session().getStartTime())) {
            throw new GiftException(GIFT_SCOPE_UNAVAILABLE, "GIFT allocation requires a published paid activity and an unstarted scheduled session");
        }
    }
    private void require(boolean valid, String message) { if (!valid) { throw new GiftException(GIFT_RESOURCE_CONFLICT, message); } }
    private void changed(int count, String message) { require(count == 1, message); }
    public record Result(GiftAllocationResponse allocation, boolean created) { }
}
