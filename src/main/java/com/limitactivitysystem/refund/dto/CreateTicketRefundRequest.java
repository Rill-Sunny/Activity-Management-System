package com.limitactivitysystem.refund.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.util.List;

public class CreateTicketRefundRequest {
    @NotBlank @Size(max = 64) private String requestId;
    @NotNull @Positive private Long orderId;
    @NotEmpty private List<@NotNull @Positive Long> ticketIds;
    @NotBlank @Size(max = 500) private String reason;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public List<Long> getTicketIds() { return ticketIds; }
    public void setTicketIds(List<Long> ticketIds) { this.ticketIds = ticketIds; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Refund accepts only requestId, orderId, ticketIds and reason");
    }
}
