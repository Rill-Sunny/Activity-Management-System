package com.limitactivitysystem.ticketorder.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.limitactivitysystem.ticketorder.validation.ValidTicketOrderItem;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@ValidTicketOrderItem
public class CreateTicketOrderItemRequest {

    @NotNull
    @Positive
    private Long zoneId;

    @NotNull
    private AllocationMode allocationMode;

    @Positive
    private Long requestedSeatId;

    @NotNull
    @Positive
    private Integer quantity;

    @NotNull
    private Boolean acceptNonAdjacent;

    public Long getZoneId() { return zoneId; }
    public void setZoneId(Long zoneId) { this.zoneId = zoneId; }
    public AllocationMode getAllocationMode() { return allocationMode; }
    public void setAllocationMode(AllocationMode allocationMode) { this.allocationMode = allocationMode; }
    public Long getRequestedSeatId() { return requestedSeatId; }
    public void setRequestedSeatId(Long requestedSeatId) { this.requestedSeatId = requestedSeatId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Boolean getAcceptNonAdjacent() { return acceptNonAdjacent; }
    public void setAcceptNonAdjacent(Boolean acceptNonAdjacent) { this.acceptNonAdjacent = acceptNonAdjacent; }

    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException(
                "Each order item accepts only zoneId, allocationMode, requestedSeatId, quantity and acceptNonAdjacent");
    }
}
