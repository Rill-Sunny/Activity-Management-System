package com.limitactivitysystem.product.service;

import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.product.dto.ProductOrderResponse;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.product.exception.ProductOrderOutOfStockException;
import com.limitactivitysystem.product.mapper.ProductMapper;
import com.limitactivitysystem.product.mapper.ProductOrderMapper;
import com.limitactivitysystem.product.mapper.SkuMapper;
import com.limitactivitysystem.product.mapper.UserProductPurchaseMapper;
import com.limitactivitysystem.product.model.Product;
import com.limitactivitysystem.product.model.ProductOrder;
import com.limitactivitysystem.product.model.ProductOrderItem;
import com.limitactivitysystem.product.model.Sku;
import com.limitactivitysystem.product.service.ProductQualificationService;
import com.limitactivitysystem.qualification.service.QualificationGuardService;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static com.limitactivitysystem.product.exception.ProductErrorCode.*;

@Service
@Validated
public class ProductPaymentTransactionService {
    private final ProductOrderMapper orders;
    private final CurrentUserProvider currentUser;
    private final AppUserMapper users;
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final ProductMapper products;
    private final SkuMapper skus;
    private final UserProductPurchaseMapper purchases;
    private final ProductQualificationService qualification;
    private final QualificationGuardService guards;
    private final DatabaseTimeMapper databaseTime;

    public ProductPaymentTransactionService(ProductOrderMapper orders, CurrentUserProvider currentUser,
            AppUserMapper users, ActivityMapper activities, ActivitySessionMapper sessions, ProductMapper products,
            SkuMapper skus, UserProductPurchaseMapper purchases, ProductQualificationService qualification,
            QualificationGuardService guards, DatabaseTimeMapper databaseTime) {
        this.orders = orders; this.currentUser = currentUser; this.users = users; this.activities = activities;
        this.sessions = sessions; this.products = products; this.skus = skus; this.purchases = purchases;
        this.qualification = qualification; this.guards = guards; this.databaseTime = databaseTime;
    }

    /** A complete attempt. The outer coordinator invokes this through the Spring proxy for each retry. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProductOrderResponse payOnce(@NotNull @Positive Long orderId, @NotNull @Positive Long buyerId) {
        ProductOrder order = orders.findByIdForUpdate(orderId);
        if (order == null) { throw failure(PRODUCT_ORDER_NOT_FOUND, "Product order not found"); }
        if (!buyerId.equals(order.getBuyerId()) || !buyerId.equals(currentUser.getAuthenticatedUserId())) {
            throw failure(PRODUCT_ORDER_ACCESS_DENIED, "Only the order buyer can pay this order");
        }
        if ("PAID".equals(order.getStatus())) { return response(order); }
        if (!"PENDING_PAYMENT".equals(order.getStatus())) { throw failure(PRODUCT_ORDER_NOT_PENDING, "Product order is not pending payment"); }
        checkDeadline(order);

        List<ProductOrderItem> items = orders.findItems(orderId);
        validateItems(order, items);
        TreeSet<Long> userIds = new TreeSet<>(List.of(order.getBuyerId(), order.getPublisherId()));
        Map<Long, com.limitactivitysystem.identity.model.AppUser> lockedUsers = new HashMap<>();
        for (Long id : userIds) { lockedUsers.put(id, users.findByIdForShare(id)); }
        var buyer = lockedUsers.get(order.getBuyerId());
        var publisher = lockedUsers.get(order.getPublisherId());
        if (buyer == null || !"ACTIVE".equals(buyer.getStatus())) { throw failure(PRODUCT_USER_DISABLED, "Buyer account is not active"); }
        if (publisher == null || !"ACTIVE".equals(publisher.getStatus()) || !"PUBLISHER".equals(publisher.getRole())) {
            throw failure(PRODUCT_NOT_PURCHASABLE, "Product publisher is not active");
        }

        Map<Long, Product> lockedProducts = lockLifecycleAndProducts(order, items);
        TreeSet<Long> guardedActivities = new TreeSet<>();
        for (ProductOrderItem item : items) {
            if ("TICKET_REQUIRED".equals(item.getQualificationTypeSnapshot())) { guardedActivities.add(item.getActivityIdSnapshot()); }
        }
        Map<Long, Long> guardVersions = guards.lockActivities(buyerId, guardedActivities);

        // Recheck today's qualification while the lifecycle, configuration and guard rows remain locked.
        for (Product product : lockedProducts.values()) {
            var result = qualification.check(buyerId, product.productId());
            if (!result.allowed()) {
                throw failure(PRODUCT_QUALIFICATION_DENIED, "Current user is no longer eligible to buy product " + product.productId());
            }
        }
        for (ProductOrderItem item : items) {
            if ("TICKET_REQUIRED".equals(item.getQualificationTypeSnapshot())) {
                Long version = guardVersions.get(item.getActivityIdSnapshot());
                if (version == null || orders.saveQualificationGuardVersion(orderId, item.getOrderItemId(), version) != 1) {
                    throw failure(PRODUCT_ORDER_NOT_PENDING, "Ticket qualification guard snapshot is unavailable");
                }
            }
        }

        Map<Long, Integer> quantityByProduct = aggregate(items, ProductOrderItem::getProductId);
        for (Map.Entry<Long, Integer> entry : new TreeMap<>(quantityByProduct).entrySet()) {
            Product product = lockedProducts.get(entry.getKey());
            int quantity = entry.getValue();
            purchases.ensureExists(buyerId, product.productId());
            if (product.purchaseLimit() == null || product.purchaseLimit() <= 0
                    || purchases.tryIncrease(buyerId, product.productId(), quantity, product.purchaseLimit()) != 1) {
                throw failure(PRODUCT_PURCHASE_LIMIT_EXCEEDED, "Product purchase limit would be exceeded");
            }
        }

        Map<Long, Integer> quantityBySku = aggregate(items, ProductOrderItem::getSkuId);
        Map<Long, Sku> lockedSkus = new TreeMap<>();
        for (Long skuId : quantityBySku.keySet()) {
            Sku sku = skus.findByIdForUpdate(skuId);
            ProductOrderItem item = items.stream().filter(value -> skuId.equals(value.getSkuId())).findFirst().orElseThrow();
            if (sku == null || !item.getProductId().equals(sku.productId()) || !"ACTIVE".equals(sku.status())) {
                throw failure(SKU_NOT_PURCHASABLE, "An ordered SKU is no longer available for sale");
            }
            lockedSkus.put(skuId, sku);
        }

        List<ProductOrderOutOfStockException.UnavailableItem> unavailable = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : quantityBySku.entrySet()) {
            ProductOrderItem item = items.stream().filter(value -> entry.getKey().equals(value.getSkuId())).findFirst().orElseThrow();
            Sku sku = lockedSkus.get(entry.getKey());
            if (skus.decreaseStock(entry.getKey(), item.getProductId(), entry.getValue()) != 1) {
                if (sku.remainsStock() < entry.getValue()) {
                    unavailable.add(new ProductOrderOutOfStockException.UnavailableItem(entry.getKey(), entry.getValue(), sku.remainsStock(), "INSUFFICIENT_STOCK"));
                } else {
                    throw failure(SKU_NOT_PURCHASABLE, "An ordered SKU could not be updated");
                }
            }
        }
        if (!unavailable.isEmpty()) { throw new ProductOrderOutOfStockException(orderId, unavailable); }

        checkDeadline(order);
        if (orders.markPaidIfPendingAndUnexpired(orderId) != 1) {
            checkDeadline(order);
            throw failure(PRODUCT_ORDER_NOT_PENDING, "Product order could not transition to paid");
        }
        // Reject and roll back if the PAID update itself crossed the database deadline.
        checkDeadline(order);
        ProductOrder paid = orders.findById(orderId);
        return response(paid);
    }

    private Map<Long, Product> lockLifecycleAndProducts(ProductOrder order, List<ProductOrderItem> items) {
        TreeSet<Long> activityIds = new TreeSet<>();
        TreeSet<Long> sessionIds = new TreeSet<>();
        TreeSet<Long> productIds = new TreeSet<>();
        for (ProductOrderItem item : items) {
            activityIds.add(item.getActivityIdSnapshot()); productIds.add(item.getProductId());
            if (item.getQualificationSessionIdSnapshot() != null) { sessionIds.add(item.getQualificationSessionIdSnapshot()); }
        }
        Map<Long, com.limitactivitysystem.activity.model.Activity> lockedActivities = new HashMap<>();
        for (Long id : activityIds) {
            var activity = activities.findByIdForShare(id);
            if (activity == null || !Set.of("PUBLISHED", "FINISHED").contains(activity.getStatus())) {
                throw failure(PRODUCT_ACTIVITY_UNAVAILABLE, "An order activity is no longer available");
            }
            lockedActivities.put(id, activity);
        }
        Map<Long, com.limitactivitysystem.activity.model.ActivitySession> lockedSessions = new HashMap<>();
        for (Long id : sessionIds) {
            var session = sessions.findByIdForShare(id);
            if (session == null) { throw failure(PRODUCT_SCOPE_INVALID, "Product qualification session is missing"); }
            lockedSessions.put(id, session);
        }
        Map<Long, Product> lockedProducts = new TreeMap<>();
        for (Long id : productIds) {
            Product product = products.share(id);
            if (product == null || !"ON_SALE".equals(product.status())) {
                throw failure(PRODUCT_NOT_PURCHASABLE, "An order product is no longer on sale");
            }
            lockedProducts.put(id, product);
        }
        for (ProductOrderItem item : items) {
            Product product = lockedProducts.get(item.getProductId());
            var activity = lockedActivities.get(item.getActivityIdSnapshot());
            if (product == null || activity == null || !product.activityId().equals(item.getActivityIdSnapshot())
                    || !activity.getPublisherId().equals(product.publisherId()) || !order.getPublisherId().equals(product.publisherId())
                    || !item.getQualificationTypeSnapshot().equals(product.qualificationType())
                    || !Objects.equals(item.getQualificationScopeSnapshot(), product.qualificationScope())
                    || !Objects.equals(item.getQualificationSessionIdSnapshot(), product.qualificationSessionId())
                    || ("TICKET_REQUIRED".equals(product.qualificationType()) && !"PAID".equals(activity.getType()))) {
                throw failure(PRODUCT_SCOPE_INVALID, "Product ownership or qualification configuration changed");
            }
            if (product.qualificationSessionId() != null) {
                var session = lockedSessions.get(product.qualificationSessionId());
                if (session == null || !product.activityId().equals(session.getActivityId())) {
                    throw failure(PRODUCT_SCOPE_INVALID, "Qualification session does not belong to the product activity");
                }
            }
        }
        return lockedProducts;
    }

    private void validateItems(ProductOrder order, List<ProductOrderItem> items) {
        if (items == null || items.isEmpty()) { throw failure(INVALID_PRODUCT_ORDER, "Persisted product order has no items"); }
        BigDecimal total = BigDecimal.ZERO;
        Set<Long> skuIds = new HashSet<>();
        for (ProductOrderItem item : items) {
            if (!order.getOrderId().equals(item.getOrderId()) || item.getProductId() == null || item.getSkuId() == null
                    || item.getQuantity() == null || item.getQuantity() <= 0 || item.getUnitPrice() == null || item.getUnitPrice().signum() <= 0
                    || item.getActivityIdSnapshot() == null || !skuIds.add(item.getSkuId())) {
                throw failure(INVALID_PRODUCT_ORDER, "Persisted product order item is invalid");
            }
            total = total.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        if (order.getTotalAmount() == null || total.compareTo(order.getTotalAmount()) != 0) {
            throw failure(INVALID_PRODUCT_ORDER, "Persisted order total differs from its price snapshots");
        }
    }

    private Map<Long, Integer> aggregate(List<ProductOrderItem> items, java.util.function.Function<ProductOrderItem, Long> key) {
        Map<Long, Integer> totals = new TreeMap<>();
        for (ProductOrderItem item : items) {
            try { totals.merge(key.apply(item), item.getQuantity(), Math::addExact); }
            catch (ArithmeticException overflow) { throw failure(INVALID_PRODUCT_ORDER, "Order quantity is too large"); }
        }
        return totals;
    }

    private void checkDeadline(ProductOrder order) {
        LocalDateTime now = databaseTime.currentTime();
        if (order.getCreatedAt() == null || now.isBefore(order.getCreatedAt())) {
            throw failure(PRODUCT_ORDER_NOT_PENDING, "Product order creation time is invalid");
        }
        if (order.getExpiresAt() == null || !now.isBefore(order.getExpiresAt())) {
            throw failure(PRODUCT_ORDER_EXPIRED, "Product order payment deadline has passed");
        }
    }

    private ProductOrderResponse response(ProductOrder order) {
        return ProductOrderCreationService.response(order, orders.findItems(order.getOrderId()));
    }

    private ProductException failure(com.limitactivitysystem.product.exception.ProductErrorCode code, String message) {
        return new ProductException(code, message);
    }
}
