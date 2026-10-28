package com.limitactivitysystem.ticketorder.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public class CreateTicketOrderRequest {

    @NotBlank
    @Size(max = 64)
    private String requestId;

    @NotNull
    @Positive
    private Long sessionId;

    @NotEmpty
    private List<@NotNull @Valid CreateTicketOrderItemRequest> items;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public List<CreateTicketOrderItemRequest> getItems() { return items; }
    public void setItems(List<CreateTicketOrderItemRequest> items) { this.items = items; }

    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Order creation accepts only requestId, sessionId and items");
    }
}
