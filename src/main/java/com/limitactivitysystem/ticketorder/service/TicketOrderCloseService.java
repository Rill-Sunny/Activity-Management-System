package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.ticketorder.dto.TicketOrderResponse;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderItemMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import static com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode.*;

@Service
@Validated
public class TicketOrderCloseService {
    private final TicketOrderMapper orders;
    private final TicketOrderItemMapper items;
    private final CurrentUserProvider currentUser;
    private final AppUserMapper users;

    public TicketOrderCloseService(TicketOrderMapper orders, TicketOrderItemMapper items,
                                   CurrentUserProvider currentUser, AppUserMapper users) {
        this.orders = orders;
        this.items = items;
        this.currentUser = currentUser;
        this.users = users;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public TicketOrderResponse cancelOrder(@NotNull @Positive Long orderId) {
        var order = orders.findByIdForUpdate(orderId);
        if (order == null) { throw new TicketOrderException(ORDER_NOT_FOUND, "Ticket order not found"); }
        Long buyerId = currentUser.getAuthenticatedUserId();
        if (!buyerId.equals(order.getBuyerId())) {
            throw new TicketOrderException(ORDER_ACCESS_DENIED, "Only the buyer can cancel this order");
        }
        var user = users.findByIdForShare(buyerId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new TicketOrderException(USER_DISABLED, "Current account is not active");
        }
        if (!"PENDING_PAYMENT".equals(order.getStatus())) {
            throw new TicketOrderException(ORDER_NOT_PENDING, "Only pending payment orders can be cancelled");
        }
        if (orders.closePendingForBuyer(orderId, buyerId) != 1) {
            throw new IllegalStateException("Locked pending order could not be cancelled");
        }
        // Pending orders own no stock, seats, purchase quota or tickets to release.
        return TicketOrderResponses.from(orders.findById(orderId), items.findByOrderId(orderId));
    }

    /** One independent transaction per trigger; safe to retry or invoke from a future MQ consumer. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean closeExpiredOrder(@NotNull @Positive Long orderId) {
        return orders.closePendingIfExpired(orderId) == 1;
    }
}
