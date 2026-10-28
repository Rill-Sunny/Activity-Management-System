package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.ticketorder.dto.CreateTicketOrderRequest;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import com.limitactivitysystem.ticketorder.model.TicketOrderItem;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Option A: compare a sorted multiset of item intents, preserving quantities and duplicate rows. */
final class TicketOrderIntent {
    private TicketOrderIntent() { }

    private record Item(Long zoneId, String allocationMode, Long requestedSeatId,
                        Integer quantity, Boolean acceptNonAdjacent) { }

    private static final Comparator<Item> ORDER = Comparator.comparing(Item::zoneId)
            .thenComparing(Item::allocationMode)
            .thenComparing(Item::requestedSeatId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Item::quantity)
            .thenComparing(Item::acceptNonAdjacent);

    static boolean matches(TicketOrder order, List<TicketOrderItem> stored, CreateTicketOrderRequest request) {
        if (!Objects.equals(order.getSessionId(), request.getSessionId())) { return false; }
        var expected = stored.stream().map(item -> new Item(item.getZoneId(), item.getAllocationMode(),
                item.getRequestedSeatId(), item.getQuantity(), item.getAcceptNonAdjacent())).sorted(ORDER).toList();
        var actual = request.getItems().stream().map(item -> new Item(item.getZoneId(), item.getAllocationMode().name(),
                item.getRequestedSeatId(), item.getQuantity(), item.getAcceptNonAdjacent())).sorted(ORDER).toList();
        return expected.equals(actual);
    }
}
