package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.ticketorder.dto.TicketPaymentResponse;
import com.limitactivitysystem.ticketorder.dto.TicketResponse;
import com.limitactivitysystem.ticketorder.mapper.TicketMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderItemMapper;
import com.limitactivitysystem.ticketorder.model.Ticket;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import com.limitactivitysystem.ticketorder.model.TicketOrderItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TicketIssuanceService {
    private final TicketMapper tickets;
    private final TicketOrderItemMapper items;
    public TicketIssuanceService(TicketMapper tickets, TicketOrderItemMapper items) { this.tickets = tickets; this.items = items; }

    public void createTickets(TicketOrder order, List<TicketOrderItem> saved, List<SeatAllocator.Allocation> allocated) {
        Map<Long, TicketOrderItem> byId = new HashMap<>();
        saved.forEach(item -> byId.put(item.getOrderItemId(), item));
        Map<Long, Integer> counts = new HashMap<>();
        var seatIds = new HashSet<Long>();
        for (var allocation : allocated) {
            TicketOrderItem item = byId.get(allocation.orderItemId());
            if (item == null || !order.getOrderId().equals(item.getOrderId())
                    || !item.getZoneId().equals(allocation.zoneId()) || !seatIds.add(allocation.seatId())) {
                throw new IllegalStateException("Seat allocation does not match persisted order items");
            }
            counts.merge(item.getOrderItemId(), 1, Math::addExact);
        }
        verifyCountsAndAmount(order, saved, counts);
        for (var allocation : allocated) {
            Ticket ticket = new Ticket();
            ticket.setOrderItemId(allocation.orderItemId()); ticket.setSeatId(allocation.seatId());
            ticket.setPaidPrice(byId.get(allocation.orderItemId()).getUnitPrice()); ticket.setStatus("VALID");
            if (tickets.insert(ticket) != 1) { throw new IllegalStateException("Ticket insert did not affect one row"); }
        }
        verifyPersistedTickets(order, saved, tickets.findByOrderId(order.getOrderId()));
    }

    public TicketPaymentResponse response(TicketOrder order) {
        List<Ticket> issued = tickets.findByOrderId(order.getOrderId());
        verifyPersistedTickets(order, items.findByOrderId(order.getOrderId()), issued);
        return new TicketPaymentResponse(order.getOrderId(), order.getSessionId(), order.getTotalAmount(),
                order.getStatus(), order.getPaidAt(), issued.stream().map(ticket -> new TicketResponse(ticket.getTicketId(),
                ticket.getOrderItemId(), ticket.getSeatId(), ticket.getPaidPrice(), ticket.getStatus(), ticket.getIssuedAt())).toList());
    }

    private void verifyPersistedTickets(TicketOrder order, List<TicketOrderItem> saved, List<Ticket> issued) {
        Map<Long, BigDecimal> prices = new HashMap<>();
        saved.forEach(item -> prices.put(item.getOrderItemId(), item.getUnitPrice()));
        Map<Long, Integer> counts = new HashMap<>();
        var seats = new HashSet<Long>();
        for (Ticket ticket : issued) {
            BigDecimal price = prices.get(ticket.getOrderItemId());
            if (price == null || price.compareTo(ticket.getPaidPrice()) != 0 || !seats.add(ticket.getSeatId())
                    || !"VALID".equals(ticket.getStatus())) { throw new IllegalStateException("Persisted tickets do not match the paid order"); }
            counts.merge(ticket.getOrderItemId(), 1, Math::addExact);
        }
        verifyCountsAndAmount(order, saved, counts);
    }

    private void verifyCountsAndAmount(TicketOrder order, List<TicketOrderItem> saved, Map<Long, Integer> counts) {
        if (saved.isEmpty() || counts.size() != saved.size()) { throw new IllegalStateException("Ticket count does not match order items"); }
        BigDecimal total = BigDecimal.ZERO;
        for (TicketOrderItem item : saved) {
            if (!item.getQuantity().equals(counts.get(item.getOrderItemId())) || item.getUnitPrice() == null
                    || item.getUnitPrice().signum() <= 0) { throw new IllegalStateException("Ticket quantity or snapshot price is invalid"); }
            total = total.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        if (total.compareTo(order.getTotalAmount()) != 0) { throw new IllegalStateException("Order total does not match snapshot items"); }
    }
}
