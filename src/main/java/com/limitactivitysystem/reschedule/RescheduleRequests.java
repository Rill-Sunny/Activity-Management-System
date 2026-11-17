package com.limitactivitysystem.reschedule;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

public final class RescheduleRequests {
    private RescheduleRequests() { }
    public record Create(@NotBlank @Size(max=64) String requestId,
                         @NotNull OffsetDateTime newStartTime, @NotNull OffsetDateTime newEndTime,
                         @NotBlank @Size(max=500) String reason) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name,Object value) { throw new IllegalArgumentException("Unexpected reschedule field: "+name); }
    }
    public record Confirm(@NotEmpty @Size(max=100) List<@NotNull @Positive Long> ticketIds) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name,Object value) { throw new IllegalArgumentException("Unexpected confirmation field: "+name); }
    }
    public record Refund(@NotBlank @Size(max=64) String requestId,
                         @NotEmpty @Size(max=100) List<@NotNull @Positive Long> ticketIds) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name,Object value) { throw new IllegalArgumentException("Unexpected refund field: "+name); }
    }
}
