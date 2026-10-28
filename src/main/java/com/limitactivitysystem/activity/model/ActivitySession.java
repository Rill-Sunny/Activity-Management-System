package com.limitactivitysystem.activity.model;

import java.time.LocalDateTime;

public class ActivitySession {

    private Long activitySessionId;
    private Long activityId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer capacity;
    private String status = "SCHEDULED";
    private Integer maxTicketsPerUser;
    private String seatMode;

    public Long getActivitySessionId() {
        return activitySessionId;
    }

    public void setActivitySessionId(Long activitySessionId) {
        this.activitySessionId = activitySessionId;
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
}
