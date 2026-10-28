package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.mapper.SessionSeatMapper;
import com.limitactivitysystem.activity.mapper.SessionZoneMapper;
import com.limitactivitysystem.activity.model.SessionSeat;
import com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.model.TicketOrderItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode.*;

@Service
public class SeatAllocator {
    private final ActivitySessionMapper sessions;
    private final SessionZoneMapper zones;
    private final SessionSeatMapper seats;
    private final TicketPaymentResourceService resources;
    private final SelectedSeatAllocator selected;
    private final AutoSeatAllocator automatic;

    public SeatAllocator(ActivitySessionMapper sessions, SessionZoneMapper zones, SessionSeatMapper seats,
                         TicketPaymentResourceService resources, SelectedSeatAllocator selected, AutoSeatAllocator automatic) {
        this.sessions = sessions;
        this.zones = zones;
        this.seats = seats;
        this.resources = resources;
        this.selected = selected;
        this.automatic = automatic;
    }

    /** Caller has already locked the order, checked eligibility and acquired purchase quota in this transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Allocation> allocate(Long sessionId, List<TicketOrderItem> items) {
        if (!Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
                .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw new IllegalTransactionStateException("Seat allocation requires an explicit READ_COMMITTED transaction");
        }
        if (sessionId == null || sessionId <= 0 || items == null || items.isEmpty()) {
            throw failure(INVALID_ORDER_ITEM, "A session and persisted order items are required");
        }
        var session = sessions.findById(sessionId);
        if (session == null) { throw failure(SESSION_NOT_FOUND, "Session not found"); }
        String mode = session.getSeatMode();
        if (!"SELECT_SEAT".equals(mode) && !"AUTO_ASSIGN".equals(mode)) {
            throw failure(INVALID_SESSION_CONFIG, "Session does not have a paid seat mode");
        }
        Map<Long, Integer> quantities = new TreeMap<>();
        var itemIds = new HashSet<Long>();
        Long orderId = null;
        for (TicketOrderItem item : items) {
            if (item == null || item.getOrderItemId() == null || item.getOrderItemId() <= 0
                    || item.getOrderId() == null || item.getOrderId() <= 0
                    || item.getZoneId() == null || item.getZoneId() <= 0
                    || item.getQuantity() == null || item.getQuantity() <= 0 || item.getAcceptNonAdjacent() == null
                    || !itemIds.add(item.getOrderItemId())) {
                throw failure(INVALID_ORDER_ITEM, "Persisted order item is invalid");
            }
            if (orderId == null) { orderId = item.getOrderId(); }
            if (!Objects.equals(orderId, item.getOrderId())) { throw failure(INVALID_ORDER_ITEM, "Items belong to different orders"); }
            if (!mode.equals(item.getAllocationMode())) { throw failure(INVALID_ALLOCATION_MODE, "Item mode must match session"); }
            if (mode.equals("SELECT_SEAT") && (item.getRequestedSeatId() == null || item.getRequestedSeatId() <= 0
                    || item.getQuantity() != 1 || item.getAcceptNonAdjacent())
                    || mode.equals("AUTO_ASSIGN") && item.getRequestedSeatId() != null) {
                throw failure(INVALID_ORDER_ITEM, "Saved seat intent is invalid");
            }
            try { quantities.merge(item.getZoneId(), item.getQuantity(), Math::addExact); }
            catch (ArithmeticException overflow) { throw failure(INVALID_ORDER_ITEM, "Zone quantity is too large"); }
        }

        // Lock every Zone before acquiring any Seat, including orders whose Seat IDs interleave across Zones.
        for (Long zoneId : quantities.keySet()) {
            var zone = zones.findByIdForUpdate(zoneId);
            if (zone == null) { throw failure(ZONE_NOT_FOUND, "Paid zone not found"); }
            if (!sessionId.equals(zone.getSessionId())) { throw failure(INVALID_ZONE_FOR_SESSION, "Zone belongs to another session"); }
            if (!"PAID".equals(zone.getZoneType())) { throw failure(GIFT_ZONE_NOT_PURCHASABLE, "Gift zone cannot be purchased"); }
        }
        quantities.forEach(resources::decreaseStock);

        var used = new HashSet<Long>();
        Map<Long, List<SessionSeat>> available = new HashMap<>();
        List<Allocation> allocations = new ArrayList<>();
        for (TicketOrderItem item : items.stream().sorted(Comparator.comparing(TicketOrderItem::getOrderItemId)).toList()) {
            List<SessionSeat> chosen;
            if (mode.equals("SELECT_SEAT")) {
                chosen = List.of(selected.select(item, seats.findById(item.getRequestedSeatId())));
            } else {
                var current = available.computeIfAbsent(item.getZoneId(), seats::findAvailableByZoneId);
                chosen = automatic.select(current.stream().filter(seat -> !used.contains(seat.getSeatId())).toList(),
                        item.getQuantity(), item.getAcceptNonAdjacent());
            }
            for (SessionSeat seat : chosen) {
                if (!used.add(seat.getSeatId())) { throw failure(DUPLICATE_REQUESTED_SEAT, "Seat appears in multiple items"); }
                allocations.add(new Allocation(item.getOrderItemId(), item.getZoneId(), seat.getSeatId()));
            }
        }
        allocations.sort(Comparator.comparing(Allocation::seatId));
        for (Allocation allocation : allocations) {
            if (seats.occupySeat(allocation.seatId(), allocation.zoneId()) != 1) {
                throw failure(SEAT_NOT_AVAILABLE, "Seat could not be acquired");
            }
        }
        return List.copyOf(allocations);
    }

    private TicketOrderException failure(TicketOrderErrorCode code, String message) {
        return new TicketOrderException(code, message);
    }

    public record Allocation(Long orderItemId, Long zoneId, Long seatId) { }
}
