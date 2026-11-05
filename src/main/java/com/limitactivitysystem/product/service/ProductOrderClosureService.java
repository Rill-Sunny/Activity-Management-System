package com.limitactivitysystem.product.service;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.product.dto.ProductOrderResponse;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.product.mapper.ProductOrderMapper;
import com.limitactivitysystem.security.CurrentUserProvider;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import static com.limitactivitysystem.product.exception.ProductErrorCode.*;

@Service
@Validated
public class ProductOrderClosureService {
    private final ProductOrderMapper orders;
    private final AppUserMapper users;
    private final CurrentUserProvider currentUser;

    public ProductOrderClosureService(ProductOrderMapper orders, AppUserMapper users, CurrentUserProvider currentUser) {
        this.orders = orders; this.users = users; this.currentUser = currentUser;
    }

    /** Runs after the failed payment attempt has rolled back, in a new short transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProductOrderResponse closeAfterOutOfStock(@NotNull @Positive Long orderId, @NotNull @Positive Long buyerId) {
        var order = orders.findByIdForUpdate(orderId);
        if (order == null) { throw new ProductException(PRODUCT_ORDER_NOT_FOUND, "Product order not found"); }
        if (!buyerId.equals(currentUser.getAuthenticatedUserId()) || !buyerId.equals(order.getBuyerId())) {
            throw new ProductException(PRODUCT_ORDER_ACCESS_DENIED, "Only the order buyer can pay this order");
        }
        var user = users.findByIdForShare(buyerId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new ProductException(PRODUCT_USER_DISABLED, "Buyer account is not active");
        }
        if ("PENDING_PAYMENT".equals(order.getStatus()) && orders.closePendingForOutOfStock(orderId) != 1) {
            throw new IllegalStateException("Locked pending product order could not be closed");
        }
        return ProductOrderCreationService.response(orders.findById(orderId), orders.findItems(orderId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean closeExpiredOrder(@NotNull @Positive Long orderId) {
        return orders.closePendingIfExpired(orderId) == 1;
    }
}
