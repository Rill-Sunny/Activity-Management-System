package com.limitactivitysystem.refund.service;

import com.limitactivitysystem.refund.mapper.TicketRefundMapper;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketRefundFailureService {
    private final TicketOrderMapper orders;
    private final TicketRefundMapper refunds;
    private final DatabaseTimeMapper time;

    public TicketRefundFailureService(TicketOrderMapper orders, TicketRefundMapper refunds, DatabaseTimeMapper time) {
        this.orders = orders; this.refunds = refunds; this.time = time;
    }

    /** A separate short transaction. Never overwrites a concurrently completed refund. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void record(Long refundId, Long buyerId, String reason) {
        var discovered = refunds.findById(refundId);
        if (discovered == null || !buyerId.equals(discovered.getBuyerId())) { return; }
        var order = orders.findByIdForUpdate(discovered.getOrderId());
        if (order == null || !buyerId.equals(order.getBuyerId())) { return; }
        var request = refunds.findByIdForUpdate(refundId);
        if (request == null || !buyerId.equals(request.getBuyerId())
                || !order.getOrderId().equals(request.getOrderId()) || !"PENDING".equals(request.getStatus())) { return; }
        var items = refunds.findItemsForUpdate(refundId);
        if (items.isEmpty() || items.stream().anyMatch(item -> !"PENDING".equals(item.status()))) { return; }
        var now = time.currentTime();
        for (var item : items) {
            if (refunds.markItemFailed(item.refundItemId(), now, reason) != 1) {
                throw new IllegalStateException("Locked refund item could not record technical failure");
            }
        }
        if (refunds.markFailed(refundId, now) != 1) {
            throw new IllegalStateException("Locked refund request could not record technical failure");
        }
    }
}
