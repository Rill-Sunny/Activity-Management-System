package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.activity.model.SessionSeat;
import com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure candidate selection. SeatAllocator holds all Zone locks before calling this component. */
@Component
public class AutoSeatAllocator {
    private static final Comparator<SessionSeat> PRIORITY = Comparator.comparing(SessionSeat::getAllocationPriority)
            .thenComparing(SessionSeat::getRowNo).thenComparing(SessionSeat::getSeatNo)
            .thenComparing(SessionSeat::getSeatId);
    private record Position(Long zoneId, int row, int seat) { }

    public List<SessionSeat> select(List<SessionSeat> available, int quantity, boolean acceptNonAdjacent) {
        var ordered = available.stream().filter(seat -> "AVAILABLE".equals(seat.getAllocationStatus()))
                .sorted(PRIORITY).toList();
        if (quantity <= 0 || ordered.size() < quantity) { throw unsatisfied(); }
        Map<Position, SessionSeat> positions = new HashMap<>();
        ordered.forEach(seat -> positions.put(new Position(seat.getZoneId(), seat.getRowNo(), seat.getSeatNo()), seat));
        for (SessionSeat start : ordered) {
            if ((long) start.getSeatNo() + quantity - 1 > Integer.MAX_VALUE) { continue; }
            var block = new java.util.ArrayList<SessionSeat>(quantity);
            for (int offset = 0; offset < quantity; offset++) {
                SessionSeat next = positions.get(new Position(start.getZoneId(), start.getRowNo(), start.getSeatNo() + offset));
                if (next == null) { break; }
                block.add(next);
            }
            if (block.size() == quantity) { return List.copyOf(block); }
        }
        if (acceptNonAdjacent) { return List.copyOf(ordered.subList(0, quantity)); }
        throw new TicketOrderException(TicketOrderErrorCode.ADJACENT_SEATS_UNAVAILABLE,
                "No adjacent seats satisfy the saved order preference");
    }

    private TicketOrderException unsatisfied() {
        return new TicketOrderException(TicketOrderErrorCode.SEAT_REQUIREMENT_NOT_SATISFIED,
                "Available seats cannot satisfy the saved seat requirements");
    }
}
