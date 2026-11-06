package com.limitactivitysystem.product.service;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.product.dto.ProductFulfillmentRequest;
import com.limitactivitysystem.product.dto.ProductFulfillmentResponse;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.product.mapper.ProductOrderMapper;
import com.limitactivitysystem.product.model.ProductOrder;
import com.limitactivitysystem.product.model.ProductOrderItem;
import com.limitactivitysystem.security.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static com.limitactivitysystem.product.exception.ProductErrorCode.*;

@Service
@Validated
public class ProductFulfillmentService {
    private final CurrentUserProvider currentUser;
    private final AppUserMapper users;
    private final ProductOrderMapper orders;

    public ProductFulfillmentService(CurrentUserProvider currentUser, AppUserMapper users, ProductOrderMapper orders) {
        this.currentUser = currentUser;
        this.users = users;
        this.orders = orders;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProductFulfillmentResponse ship(@NotNull @Positive Long orderId, @NotNull @Valid ProductFulfillmentRequest request) {
        Long publisherId = currentUser.getAuthenticatedUserId();
        ProductOrder order = orders.findByIdForUpdate(orderId);
        if (order == null) { throw failure(PRODUCT_ORDER_NOT_FOUND, "Product order not found"); }
        if (!publisherId.equals(order.getPublisherId())) { throw failure(PRODUCT_ORDER_ACCESS_DENIED, "Only the owning publisher can ship this order"); }
        var publisher = users.findByIdForShare(publisherId);
        if (publisher == null || !"ACTIVE".equals(publisher.getStatus()) || !"PUBLISHER".equals(publisher.getRole())) {
            throw failure(PRODUCT_USER_DISABLED, "Current publisher account is not active");
        }
        requirePaid(order);
        List<ProductOrderItem> items = orders.findItemsForUpdate(orderId);
        ProductOrderItem item = findItem(items, request.orderItemId());
        int unrefunded = item.getQuantity() - item.getRefundedQuantity();
        if (!"UNSHIPPED".equals(item.getFulfillmentStatus()) || item.getShippedQuantity() != 0
                || item.getRefundPendingQuantity() != 0 || request.quantity() != unrefunded) {
            throw failure(PRODUCT_FULFILLMENT_INVALID, "Ship the full remaining quantity of an unshipped order item");
        }
        changed(orders.markShipped(orderId, item.getOrderItemId(), request.quantity()),
                "Order item could not be marked shipped");
        return response(orderId, orders.findItemsForUpdate(orderId).stream()
                .filter(value -> value.getOrderItemId().equals(item.getOrderItemId())).findFirst().orElseThrow());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProductFulfillmentResponse receive(@NotNull @Positive Long orderId, @NotNull @Valid ProductFulfillmentRequest request) {
        Long buyerId = currentUser.getAuthenticatedUserId();
        ProductOrder order = orders.findByIdForUpdate(orderId);
        if (order == null) { throw failure(PRODUCT_ORDER_NOT_FOUND, "Product order not found"); }
        if (!buyerId.equals(order.getBuyerId())) { throw failure(PRODUCT_ORDER_ACCESS_DENIED, "Only the order buyer can confirm receipt"); }
        var buyer = users.findByIdForShare(buyerId);
        if (buyer == null || !"ACTIVE".equals(buyer.getStatus()) || !"USER".equals(buyer.getRole())) {
            throw failure(PRODUCT_USER_DISABLED, "Current buyer account is not active");
        }
        requirePaid(order);
        List<ProductOrderItem> items = orders.findItemsForUpdate(orderId);
        ProductOrderItem item = findItem(items, request.orderItemId());
        if (!"SHIPPED".equals(item.getFulfillmentStatus()) || item.getReceivedQuantity() != 0
                || request.quantity() != item.getShippedQuantity()) {
            throw failure(PRODUCT_FULFILLMENT_INVALID, "Confirm the full shipped quantity of this order item");
        }
        changed(orders.markReceived(orderId, item.getOrderItemId(), request.quantity()),
                "Order item could not be marked received");
        return response(orderId, orders.findItemsForUpdate(orderId).stream()
                .filter(value -> value.getOrderItemId().equals(item.getOrderItemId())).findFirst().orElseThrow());
    }

    private void requirePaid(ProductOrder order) {
        if (!List.of("PAID", "PARTIALLY_REFUNDED").contains(order.getStatus())) {
            throw failure(PRODUCT_ORDER_NOT_PAID, "Only a paid product order can be fulfilled");
        }
    }

    private ProductOrderItem findItem(List<ProductOrderItem> items, Long orderItemId) {
        return items.stream().filter(value -> orderItemId.equals(value.getOrderItemId())).findFirst()
                .orElseThrow(() -> failure(PRODUCT_ORDER_ITEM_NOT_FOUND, "Order item does not belong to this order"));
    }

    private ProductFulfillmentResponse response(Long orderId, ProductOrderItem item) {
        return new ProductFulfillmentResponse(orderId, item.getOrderItemId(), item.getSkuId(),
                item.getShippedQuantity(), item.getReceivedQuantity(), item.getFulfillmentStatus(),
                item.getShippedAt(), item.getReceivedAt());
    }

    private void changed(int rows, String message) {
        if (rows != 1) { throw failure(PRODUCT_FULFILLMENT_INVALID, message); }
    }

    private ProductException failure(com.limitactivitysystem.product.exception.ProductErrorCode code, String message) {
        return new ProductException(code, message);
    }
}
