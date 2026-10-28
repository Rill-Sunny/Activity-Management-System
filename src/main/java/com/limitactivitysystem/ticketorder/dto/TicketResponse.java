package com.limitactivitysystem.ticketorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TicketResponse(Long ticketId, Long orderItemId, Long seatId, BigDecimal paidPrice,
                             String status, LocalDateTime issuedAt) { }
