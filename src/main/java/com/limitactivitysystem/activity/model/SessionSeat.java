package com.limitactivitysystem.activity.model;

public class SessionSeat {

    private Long seatId;
    private Long zoneId;
    private Integer rowNo;
    private Integer seatNo;
    private Integer allocationPriority;
    private String allocationStatus = "AVAILABLE";

    public Long getSeatId() {
        return seatId;
    }

    public void setSeatId(Long seatId) {
        this.seatId = seatId;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public void setZoneId(Long zoneId) {
        this.zoneId = zoneId;
    }

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

    public String getAllocationStatus() {
        return allocationStatus;
    }

    public void setAllocationStatus(String allocationStatus) {
        this.allocationStatus = allocationStatus;
    }
}
