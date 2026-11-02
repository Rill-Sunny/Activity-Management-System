package com.limitactivitysystem.gift.dto;

import java.util.List;

public record GiftAllocationPage(List<GiftAllocationResponse> allocations, Long nextAfterOperationId) { }
