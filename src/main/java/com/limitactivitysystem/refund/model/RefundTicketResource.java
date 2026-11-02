package com.limitactivitysystem.refund.model;

/** Non-locking discovery of the resources to acquire before the Ticket lock. */
public record RefundTicketResource(Long ticketId, Long orderItemId, Long orderId, Long buyerId,
                                   Long sessionId, Long zoneId, Long seatId) { }
