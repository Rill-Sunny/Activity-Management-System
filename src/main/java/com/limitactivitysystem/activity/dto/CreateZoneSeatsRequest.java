package com.limitactivitysystem.activity.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public class CreateZoneSeatsRequest {

    @NotEmpty
    private List<@NotNull @Valid SeatDefinition> seats;

    public List<SeatDefinition> getSeats() {
        return seats;
    }

    public void setSeats(List<SeatDefinition> seats) {
        this.seats = seats;
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Seat creation accepts only seats");
    }

    public static class SeatDefinition {

        @NotNull
        @Positive
        private Integer rowNo;

        @NotNull
        @Positive
        private Integer seatNo;

        @NotNull
        @Positive
        private Integer allocationPriority;

        public Integer getRowNo() {
            return rowNo;
        }

        public void setRowNo(Integer rowNo) {
            this.rowNo = rowNo;
        }

        public Integer getSeatNo() {
            return seatNo;
        }

        public void setSeatNo(Integer seatNo) {
            this.seatNo = seatNo;
        }

        public Integer getAllocationPriority() {
            return allocationPriority;
        }

        public void setAllocationPriority(Integer allocationPriority) {
            this.allocationPriority = allocationPriority;
        }

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Each seat accepts only rowNo, seatNo and allocationPriority");
        }
    }
}
