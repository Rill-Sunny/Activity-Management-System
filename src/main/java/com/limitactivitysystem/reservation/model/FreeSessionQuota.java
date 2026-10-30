package com.limitactivitysystem.reservation.model;

public class FreeSessionQuota {
    private Long sessionId;
    private Integer capacity;
    private Integer remaining;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public Integer getRemaining() { return remaining; }
    public void setRemaining(Integer remaining) { this.remaining = remaining; }
}
