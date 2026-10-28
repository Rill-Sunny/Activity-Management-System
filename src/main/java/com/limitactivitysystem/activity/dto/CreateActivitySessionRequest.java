package com.limitactivitysystem.activity.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

public class CreateActivitySessionRequest {

    @NotNull
    private LocalDateTime startTime;

    @NotNull
    private LocalDateTime endTime;

    @NotNull
    @Positive
    private Integer capacity;

    private Integer maxTicketsPerUser;
    private String seatMode;

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public Integer getMaxTicketsPerUser() {
        return maxTicketsPerUser;
    }

    public void setMaxTicketsPerUser(Integer maxTicketsPerUser) {
        this.maxTicketsPerUser = maxTicketsPerUser;
    }

    public String getSeatMode() {
        return seatMode;
    }

    public void setSeatMode(String seatMode) {
        this.seatMode = seatMode;
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException(
                "Session creation accepts only startTime, endTime, capacity, maxTicketsPerUser and seatMode");
    }
}
