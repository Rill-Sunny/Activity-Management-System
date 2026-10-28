package com.limitactivitysystem.ticketorder.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Ticket {
    private Long ticketId;
    private Long orderItemId;
    private Long seatId;
    private BigDecimal paidPrice;
    private String status;
    private LocalDateTime issuedAt;
    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public Long getOrderItemId() { return orderItemId; }
    public void setOrderItemId(Long orderItemId) { this.orderItemId = orderItemId; }
    public Long getSeatId() { return seatId; }
    public void setSeatId(Long seatId) { this.seatId = seatId; }
    public BigDecimal getPaidPrice() { return paidPrice; }
    public void setPaidPrice(BigDecimal paidPrice) { this.paidPrice = paidPrice; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime issuedAt) { this.issuedAt = issuedAt; }
}
