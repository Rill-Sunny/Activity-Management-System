package com.limitactivitysystem.gift.controller;

import com.limitactivitysystem.gift.dto.*;
import com.limitactivitysystem.gift.service.GiftAllocationService;
import com.limitactivitysystem.gift.service.GiftAllocationQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class PublisherGiftAllocationController {
    private final GiftAllocationService allocations;
    private final GiftAllocationQueryService queries;
    public PublisherGiftAllocationController(GiftAllocationService allocations, GiftAllocationQueryService queries) { this.allocations = allocations; this.queries = queries; }
    @PostMapping("/publisher/gift-zones/{zoneId}/allocations")
    public ResponseEntity<GiftAllocationResponse> allocate(@PathVariable @Positive Long zoneId, @RequestBody @Valid AllocateGiftSeatsRequest request) {
        var result = allocations.allocate(zoneId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.allocation());
    }
    @GetMapping("/publisher/gift-zones/{zoneId}/allocations")
    public GiftAllocationPage list(@PathVariable @Positive Long zoneId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @PositiveOrZero long afterOperationId) {
        return queries.list(zoneId, limit, afterOperationId);
    }
    @GetMapping("/publisher/gift-allocations/{operationId}")
    public GiftAllocationResponse get(@PathVariable @Positive Long operationId) { return queries.get(operationId); }
}
