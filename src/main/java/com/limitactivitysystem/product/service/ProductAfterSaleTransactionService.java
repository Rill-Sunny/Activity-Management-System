package com.limitactivitysystem.product.service;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.product.dto.CreateProductAfterSaleRequest;
import com.limitactivitysystem.product.dto.ProductAfterSaleResponse;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.product.mapper.ProductAfterSaleMapper;
import com.limitactivitysystem.product.mapper.ProductOrderMapper;
import com.limitactivitysystem.product.mapper.SkuMapper;
import com.limitactivitysystem.product.mapper.UserProductPurchaseMapper;
import com.limitactivitysystem.product.model.ProductAfterSaleItem;
import com.limitactivitysystem.product.model.ProductAfterSaleRequest;
import com.limitactivitysystem.product.model.ProductOrder;
import com.limitactivitysystem.product.model.ProductOrderItem;
import com.limitactivitysystem.product.model.Sku;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.limitactivitysystem.product.exception.ProductErrorCode.*;

@Service
@Validated
public class ProductAfterSaleTransactionService {
    private final CurrentUserProvider currentUser;
    private final AppUserMapper users;
    private final ProductOrderMapper orders;
    private final ProductAfterSaleMapper afterSales;
    private final UserProductPurchaseMapper purchases;
    private final SkuMapper skus;
    private final DatabaseTimeMapper databaseTime;

    public ProductAfterSaleTransactionService(CurrentUserProvider currentUser, AppUserMapper users,
            ProductOrderMapper orders, ProductAfterSaleMapper afterSales, UserProductPurchaseMapper purchases,
            SkuMapper skus, DatabaseTimeMapper databaseTime) {
        this.currentUser = currentUser; this.users = users; this.orders = orders; this.afterSales = afterSales;
        this.purchases = purchases; this.skus = skus; this.databaseTime = databaseTime;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ProductAfterSaleResponse apply(@NotNull @Valid CreateProductAfterSaleRequest request, @NotNull @Positive Long buyerId) {
        validateRequestShape(request);
        if (!buyerId.equals(currentUser.getAuthenticatedUserId())) {
            throw failure(PRODUCT_ORDER_ACCESS_DENIED, "Authenticated buyer changed during after-sale processing");
        }
        ProductOrder order = orders.findByIdForUpdate(request.orderId());
        if (order == null) { throw failure(PRODUCT_ORDER_NOT_FOUND, "Product order not found"); }
        if (!buyerId.equals(order.getBuyerId())) { throw failure(PRODUCT_ORDER_ACCESS_DENIED, "Only the order buyer can request after-sales"); }
        var buyer = users.findByIdForShare(buyerId);
        if (buyer == null || !"ACTIVE".equals(buyer.getStatus()) || !"USER".equals(buyer.getRole())) {
            throw failure(PRODUCT_USER_DISABLED, "Current buyer account is not active");
        }
        ProductAfterSaleRequest existing = afterSales.findByBuyerAndRequestId(buyerId, request.requestId());
        if (existing != null) {
            requireSamePayload(existing, request);
            if ("SUCCEEDED".equals(existing.getStatus())) { return response(existing); }
            if (!"FAILED".equals(existing.getStatus())) { return response(existing); }
        }
        if (!List.of("PAID", "PARTIALLY_REFUNDED").contains(order.getStatus())) {
            throw failure(PRODUCT_ORDER_NOT_PAID, "After-sales require a paid product order");
        }

        List<ProductOrderItem> orderItems = orders.findItemsForUpdate(order.getOrderId());
        Map<Long, ProductOrderItem> byId = orderItems.stream().collect(Collectors.toMap(ProductOrderItem::getOrderItemId, Function.identity()));
        LocalDateTime now = databaseTime.currentTime();
        List<PlannedItem> plan = new ArrayList<>();
        for (var input : request.items().stream().sorted(Comparator.comparing(CreateProductAfterSaleRequest.Item::orderItemId)).toList()) {
            ProductOrderItem line = byId.get(input.orderItemId());
            if (line == null) { throw failure(PRODUCT_ORDER_ITEM_NOT_FOUND, "After-sale item does not belong to this order"); }
            boolean inspectionPassed = ProductAfterSaleSimulation.inspectionPassed(input);
            boolean simulateResalable = ProductAfterSaleSimulation.resalable(input);
            if (!inspectionPassed && simulateResalable) {
                throw failure(INVALID_PRODUCT_ORDER, "A failed simulated inspection cannot mark an item resalable");
            }
            boolean requiresReturn = requiresReturn(line, input, inspectionPassed, simulateResalable, now);
            int available = line.getQuantity() - line.getRefundedQuantity() - line.getRefundPendingQuantity();
            if (input.quantity() > available) {
                throw failure(PRODUCT_AFTER_SALE_QUANTITY_EXCEEDED, "Requested quantity exceeds the unrefunded quantity");
            }
            if (requiresReturn && input.quantity() > line.getReceivedQuantity() - line.getReturnedQuantity() - line.getRefundPendingQuantity()) {
                throw failure(PRODUCT_AFTER_SALE_QUANTITY_EXCEEDED, "Requested return quantity exceeds received, unreturned goods");
            }
            plan.add(new PlannedItem(line, input, inspectionPassed, simulateResalable, requiresReturn));
        }

        ProductAfterSaleRequest saved = existing;
        boolean accepted = false;
        try {
            // Database condition updates are the reservation authority; every detail must affect one row.
            for (PlannedItem item : plan) {
                changed(orders.reserveRefundQuantity(order.getOrderId(), item.line().getOrderItemId(), item.input().quantity()),
                        PRODUCT_AFTER_SALE_QUANTITY_EXCEEDED, "Requested quantity is already refunded or reserved");
            }

            if (saved == null) {
                saved = new ProductAfterSaleRequest();
                saved.setOrderId(order.getOrderId()); saved.setBuyerId(buyerId); saved.setRequestId(request.requestId());
                saved.setReason(plan.stream().map(value -> value.input().reason()).distinct().sorted().collect(Collectors.joining(",")));
                changed(afterSales.insertRequest(saved), PRODUCT_AFTER_SALE_NOT_ALLOWED, "After-sale request could not be created");
                if (saved.getAfterSaleRequestId() == null) { throw failure(PRODUCT_AFTER_SALE_NOT_ALLOWED, "After-sale request ID was not generated"); }

                List<ProductAfterSaleItem> newItems = new ArrayList<>();
                for (PlannedItem planned : plan) { newItems.add(newAfterSaleItem(saved.getAfterSaleRequestId(), planned)); }
                if (afterSales.insertItems(newItems) != newItems.size()) {
                    throw failure(PRODUCT_AFTER_SALE_NOT_ALLOWED, "Not all after-sale request items were created");
                }
            } else {
                saved = afterSales.findByIdForUpdate(saved.getAfterSaleRequestId());
                if (saved == null || !"FAILED".equals(saved.getStatus())) {
                    throw failure(PRODUCT_AFTER_SALE_NOT_ALLOWED, "Failed after-sale request is no longer retryable");
                }
                List<ProductAfterSaleItem> oldItems = afterSales.findItemsForUpdate(saved.getAfterSaleRequestId());
                changed(afterSales.reopenFailed(saved.getAfterSaleRequestId()), PRODUCT_AFTER_SALE_NOT_ALLOWED,
                        "Failed after-sale request could not be reopened");
                Map<Long, ProductAfterSaleItem> oldByOrderItem = oldItems.stream()
                        .collect(Collectors.toMap(ProductAfterSaleItem::getOrderItemId, Function.identity()));
                for (PlannedItem planned : plan) {
                    ProductAfterSaleItem old = oldByOrderItem.get(planned.line().getOrderItemId());
                    if (old == null || afterSales.reopenFailedItem(old.getAfterSaleItemId(),
                            planned.requiresReturn() ? "PENDING" : "NOT_REQUIRED") != 1) {
                        throw failure(PRODUCT_AFTER_SALE_NOT_ALLOWED, "Failed after-sale detail could not be reopened");
                    }
                }
            }
            accepted = true;
            changed(afterSales.markProcessing(saved.getAfterSaleRequestId()), PRODUCT_AFTER_SALE_NOT_ALLOWED,
                    "After-sale request could not enter processing");
            if (afterSales.markItemsProcessing(saved.getAfterSaleRequestId()) != plan.size()) {
                throw failure(PRODUCT_AFTER_SALE_NOT_ALLOWED, "All after-sale details could not enter processing");
            }
            List<ProductAfterSaleItem> storedItems = afterSales.findItemsForUpdate(saved.getAfterSaleRequestId());

            // Simulate return and inspection before any stock can be restored.
            for (int index = 0; index < plan.size(); index++) {
                PlannedItem planned = plan.get(index);
                int returned = planned.requiresReturn() ? planned.input().quantity() : 0;
                String inspection = !planned.requiresReturn() ? "NOT_REQUIRED" : (planned.simulateInspectionPassed() ? "PASSED" : "FAILED");
                Boolean resalable = planned.requiresReturn() ? planned.simulateInspectionPassed() && planned.simulateResalable() : null;
                changed(afterSales.markItemSucceeded(storedItems.get(index).getAfterSaleItemId(), returned, inspection, resalable),
                        PRODUCT_AFTER_SALE_NOT_ALLOWED, "After-sale detail could not complete simulated return, inspection, and refund");
            }

        // Restore Product quota before any SKU, matching the Day 16 payment resource order.
        Map<Long, Integer> quotaByProduct = new TreeMap<>();
        Map<Long, Integer> stockBySku = new TreeMap<>();
        for (PlannedItem item : plan) {
            int quantity = item.input().quantity();
            try {
                quotaByProduct.merge(item.line().getProductId(), quantity, Math::addExact);
                if (!item.requiresReturn() || item.simulateResalable()) {
                    stockBySku.merge(item.line().getSkuId(), quantity, Math::addExact);
                }
            } catch (ArithmeticException overflow) {
                throw failure(INVALID_PRODUCT_ORDER, "After-sale quantity total is too large");
            }
        }
        for (Map.Entry<Long, Integer> entry : quotaByProduct.entrySet()) {
            changed(purchases.tryDecrease(buyerId, entry.getKey(), entry.getValue()),
                    PRODUCT_AFTER_SALE_NOT_ALLOWED, "Product purchase quota could not be restored");
        }

        Map<Long, Sku> lockedSkus = new TreeMap<>();
        for (Long skuId : stockBySku.keySet()) {
            Sku sku = skus.findByIdForUpdate(skuId);
            ProductOrderItem line = orderItems.stream().filter(value -> skuId.equals(value.getSkuId())).findFirst().orElse(null);
            if (sku == null || line == null || !line.getProductId().equals(sku.productId())) {
                throw failure(PRODUCT_AFTER_SALE_NOT_ALLOWED, "SKU no longer matches its purchased order item");
            }
            lockedSkus.put(skuId, sku);
        }
        for (Map.Entry<Long, Integer> entry : stockBySku.entrySet()) {
            Sku sku = lockedSkus.get(entry.getKey());
            changed(skus.restoreStock(entry.getKey(), sku.productId(), entry.getValue()),
                    PRODUCT_AFTER_SALE_NOT_ALLOWED, "SKU stock could not be restored without exceeding total stock");
        }

        for (int index = 0; index < plan.size(); index++) {
            PlannedItem planned = plan.get(index);
            int quantity = planned.input().quantity();
            int returned = planned.requiresReturn() ? quantity : 0;
            int restocked = planned.requiresReturn() ? (planned.simulateResalable() ? quantity : 0) : quantity;
            changed(orders.completeRefund(order.getOrderId(), planned.line().getOrderItemId(), quantity, returned, restocked),
                    PRODUCT_AFTER_SALE_NOT_ALLOWED, "Reserved refund quantity could not be completed");
        }

        Integer remaining = orders.findUnrefundedQuantity(order.getOrderId());
        changed(orders.updateRefundSummary(order.getOrderId(), remaining != null && remaining == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED"),
                PRODUCT_AFTER_SALE_NOT_ALLOWED, "Product order refund summary could not be updated");
        changed(afterSales.markSucceeded(saved.getAfterSaleRequestId()), PRODUCT_AFTER_SALE_NOT_ALLOWED,
                "After-sale request could not complete");
        return response(afterSales.findByBuyerAndRequestId(buyerId, request.requestId()));
        } catch (RuntimeException failure) {
            if (accepted) { throw new ProductAfterSaleAcceptedFailure(failure); }
            throw failure;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public ProductAfterSaleResponse findReplay(@NotNull @Valid CreateProductAfterSaleRequest request, @NotNull @Positive Long buyerId) {
        ProductAfterSaleRequest existing = afterSales.findByBuyerAndRequestId(buyerId, request.requestId());
        if (existing == null) { return null; }
        return requireSamePayload(existing, request);
    }

    private void validateRequestShape(CreateProductAfterSaleRequest request) {
        Set<Long> itemIds = new HashSet<>();
        for (var item : request.items()) {
            if (!itemIds.add(item.orderItemId())) { throw failure(INVALID_PRODUCT_ORDER, "An order item may appear only once per after-sale request"); }
        }
    }

    private ProductAfterSaleItem newAfterSaleItem(Long afterSaleRequestId, PlannedItem planned) {
        ProductAfterSaleItem item = new ProductAfterSaleItem();
        item.setAfterSaleRequestId(afterSaleRequestId); item.setOrderItemId(planned.line().getOrderItemId());
        item.setSkuId(planned.line().getSkuId()); item.setReasonCode(planned.input().reason());
        item.setRequestedQuantity(planned.input().quantity());
        item.setSimulateInspectionPassed(planned.simulateInspectionPassed()); item.setSimulateResalable(planned.simulateResalable());
        item.setStatus("PENDING"); item.setReturnedQuantity(0); item.setRefundedQuantity(0);
        item.setInspectionResult(planned.requiresReturn() ? "PENDING" : "NOT_REQUIRED"); item.setIsResalable(null);
        return item;
    }

    private boolean requiresReturn(ProductOrderItem line, CreateProductAfterSaleRequest.Item input,
                                   boolean inspectionPassed, boolean simulateResalable, LocalDateTime now) {
        if ("NO_REASON".equals(input.reason())) {
            if (!"STANDARD_7D".equals(line.getRefundPolicyTemplateSnapshot())) {
                throw failure(PRODUCT_AFTER_SALE_NOT_ALLOWED, "The special template does not accept ordinary no-reason returns");
            }
            if (!"RECEIVED".equals(line.getFulfillmentStatus())
                    || !ProductAfterSalePolicy.withinStandardSevenDayWindow(line.getReceivedAt(), now)) {
                throw failure(PRODUCT_AFTER_SALE_NOT_ALLOWED, "No-reason return requires receipt within the half-open seven-day window");
            }
            if (!inspectionPassed || !simulateResalable) {
                throw failure(PRODUCT_AFTER_SALE_NOT_ALLOWED, "No-reason return requires passed inspection and resalable goods");
            }
            return true;
        }
        if ("UNSHIPPED".equals(line.getFulfillmentStatus())) { return false; }
        if ("RECEIVED".equals(line.getFulfillmentStatus())) { return true; }
        throw failure(PRODUCT_AFTER_SALE_NOT_ALLOWED, "Shipped goods must be received before a return can be processed");
    }

    private ProductAfterSaleResponse requireSamePayload(ProductAfterSaleRequest existing, CreateProductAfterSaleRequest request) {
        if (!existing.getOrderId().equals(request.orderId())) { throw idempotencyConflict(); }
        List<ProductAfterSaleItem> saved = afterSales.findItems(existing.getAfterSaleRequestId());
        List<CreateProductAfterSaleRequest.Item> supplied = request.items().stream()
                .sorted(Comparator.comparing(CreateProductAfterSaleRequest.Item::orderItemId)).toList();
        if (saved.size() != supplied.size()) { throw idempotencyConflict(); }
        for (int i = 0; i < saved.size(); i++) {
            ProductAfterSaleItem a = saved.get(i);
            var b = supplied.get(i);
            boolean inspectionPassed = ProductAfterSaleSimulation.inspectionPassed(b);
            boolean simulateResalable = ProductAfterSaleSimulation.resalable(b);
            if (!a.getOrderItemId().equals(b.orderItemId()) || !a.getRequestedQuantity().equals(b.quantity())
                    || !a.getReasonCode().equals(b.reason())
                    || a.isSimulateInspectionPassed() != inspectionPassed || a.isSimulateResalable() != simulateResalable) {
                throw idempotencyConflict();
            }
        }
        return response(existing);
    }

    private ProductAfterSaleResponse response(ProductAfterSaleRequest request) {
        List<ProductAfterSaleResponse.Item> items = afterSales.findItems(request.getAfterSaleRequestId()).stream()
                .map(item -> new ProductAfterSaleResponse.Item(item.getOrderItemId(), item.getSkuId(), item.getReasonCode(),
                        item.getRequestedQuantity(), item.getReturnedQuantity(), item.getRefundedQuantity(), item.getReturnedAt(),
                        item.getInspectionResult(), item.getIsResalable())).toList();
        return new ProductAfterSaleResponse(request.getAfterSaleRequestId(), request.getOrderId(), request.getRequestId(),
                request.getReason(), request.getRequestedAt(), request.getStatus(), request.getFailureReason(), items);
    }

    private ProductException idempotencyConflict() {
        return failure(IDEMPOTENCY_CONFLICT, "After-sale requestId is already used for different content");
    }

    private void changed(int rows, com.limitactivitysystem.product.exception.ProductErrorCode code, String message) {
        if (rows != 1) { throw failure(code, message); }
    }

    private ProductException failure(com.limitactivitysystem.product.exception.ProductErrorCode code, String message) {
        return new ProductException(code, message);
    }

    private record PlannedItem(ProductOrderItem line, CreateProductAfterSaleRequest.Item input,
                               boolean simulateInspectionPassed, boolean simulateResalable, boolean requiresReturn) { }
}
