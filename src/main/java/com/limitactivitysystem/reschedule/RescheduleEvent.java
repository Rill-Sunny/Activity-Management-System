package com.limitactivitysystem.reschedule;

import java.time.LocalDateTime;

public class RescheduleEvent {
    private Long eventId;
    private Long sessionId;
    private String requestId;
    private LocalDateTime oldStartTime;
    private LocalDateTime oldEndTime;
    private LocalDateTime newStartTime;
    private LocalDateTime newEndTime;
    private String reason;
    private Long operatorId;
    private LocalDateTime announcementTime;
    private LocalDateTime specialRefundDeadline;
    private LocalDateTime createdAt;
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public LocalDateTime getOldStartTime() { return oldStartTime; }
    public void setOldStartTime(LocalDateTime oldStartTime) { this.oldStartTime = oldStartTime; }
    public LocalDateTime getOldEndTime() { return oldEndTime; }
    public void setOldEndTime(LocalDateTime oldEndTime) { this.oldEndTime = oldEndTime; }
    public LocalDateTime getNewStartTime() { return newStartTime; }
    public void setNewStartTime(LocalDateTime newStartTime) { this.newStartTime = newStartTime; }
    public LocalDateTime getNewEndTime() { return newEndTime; }
    public void setNewEndTime(LocalDateTime newEndTime) { this.newEndTime = newEndTime; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public LocalDateTime getAnnouncementTime() { return announcementTime; }
    public void setAnnouncementTime(LocalDateTime announcementTime) { this.announcementTime = announcementTime; }
    public LocalDateTime getSpecialRefundDeadline() { return specialRefundDeadline; }
    public void setSpecialRefundDeadline(LocalDateTime specialRefundDeadline) { this.specialRefundDeadline = specialRefundDeadline; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
