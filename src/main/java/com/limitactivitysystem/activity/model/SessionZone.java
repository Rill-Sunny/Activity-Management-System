package com.limitactivitysystem.activity.model;

import java.math.BigDecimal;

public class SessionZone {

    private Long zoneId;
    private Long sessionId;
    private String name;
    private String zoneType;
    private BigDecimal price;
    private Integer totalAmount;
    private Integer remainsAmount;

    public Long getZoneId() {
        return zoneId;
    }

    public void setZoneId(Long zoneId) {
        this.zoneId = zoneId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getZoneType() {
        return zoneType;
    }

    public void setZoneType(String zoneType) {
        this.zoneType = zoneType;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(Integer totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Integer getRemainsAmount() {
        return remainsAmount;
    }

    public void setRemainsAmount(Integer remainsAmount) {
        this.remainsAmount = remainsAmount;
    }
}
