package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.activity.model.SessionSeat;
import com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.model.TicketOrderItem;
import org.springframework.stereotype.Component;

/** Validates a selected Seat before the coordinator performs globally ordered conditional writes. */
@Component
public class SelectedSeatAllocator {
    public SessionSeat select(TicketOrderItem item, SessionSeat seat) {
        if (seat == null) {
            throw new TicketOrderException(TicketOrderErrorCode.INVALID_SEAT_RELATION, "Requested seat not found");
        }
        if (!item.getZoneId().equals(seat.getZoneId())) {
            throw new TicketOrderException(TicketOrderErrorCode.INVALID_SEAT_RELATION,
                    "Requested seat does not belong to the paid zone");
        }
        if (!"AVAILABLE".equals(seat.getAllocationStatus())) {
            throw new TicketOrderException(TicketOrderErrorCode.SEAT_NOT_AVAILABLE, "Requested seat is not available");
        }
        return seat;
    }
}
