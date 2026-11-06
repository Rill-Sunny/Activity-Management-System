package com.limitactivitysystem.product.service;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.product.dto.CreateProductAfterSaleRequest;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.product.mapper.ProductAfterSaleMapper;
import com.limitactivitysystem.product.mapper.ProductOrderMapper;
import com.limitactivitysystem.product.model.ProductAfterSaleItem;
import com.limitactivitysystem.product.model.ProductAfterSaleRequest;
import com.limitactivitysystem.security.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProductAfterSaleFailureService {
    private final CurrentUserProvider currentUser;
    private final AppUserMapper users;
    private final ProductOrderMapper orders;
    private final ProductAfterSaleMapper afterSales;

    public ProductAfterSaleFailureService(CurrentUserProvider currentUser, AppUserMapper users,
            ProductOrderMapper orders, ProductAfterSaleMapper afterSales) {
        this.currentUser = currentUser; this.users = users; this.orders = orders; this.afterSales = afterSales;
    }

    /** Runs only after the main after-sale transaction has rolled back. It stores a retryable failure without resource reservations. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void record(@NotNull @Valid CreateProductAfterSaleRequest input, @NotNull @Positive Long buyerId,
                       @NotNull RuntimeException failure) {
        if (!buyerId.equals(currentUser.getAuthenticatedUserId())) { return; }
        var order = orders.findByIdForUpdate(input.orderId());
        if (order == null || !buyerId.equals(order.getBuyerId())) { return; }
        users.findByIdForShare(buyerId);
        var lines = orders.findItemsForUpdate(order.getOrderId());
        var existing = afterSales.findByBuyerAndRequestId(buyerId, input.requestId());
        if (existing != null) { return; }

        var reasons = input.items().stream().map(CreateProductAfterSaleRequest.Item::reason).distinct().sorted()
                .collect(Collectors.joining(","));
        ProductAfterSaleRequest request = new ProductAfterSaleRequest();
        request.setOrderId(order.getOrderId()); request.setBuyerId(buyerId); request.setRequestId(input.requestId());
        request.setReason(reasons);
        if (afterSales.insertRequest(request) != 1 || request.getAfterSaleRequestId() == null) {
            throw new IllegalStateException("Accepted after-sale failure record could not be created");
        }

        List<ProductAfterSaleItem> items = new ArrayList<>();
        for (var itemInput : input.items().stream().sorted(java.util.Comparator.comparing(CreateProductAfterSaleRequest.Item::orderItemId)).toList()) {
            var line = lines.stream().filter(value -> value.getOrderItemId().equals(itemInput.orderItemId())).findFirst().orElse(null);
            if (line == null) { throw new IllegalStateException("Accepted after-sale item no longer belongs to its order"); }
            ProductAfterSaleItem item = new ProductAfterSaleItem();
            item.setAfterSaleRequestId(request.getAfterSaleRequestId()); item.setOrderItemId(line.getOrderItemId());
            item.setSkuId(line.getSkuId()); item.setReasonCode(itemInput.reason()); item.setRequestedQuantity(itemInput.quantity());
            boolean inspectionPassed = ProductAfterSaleSimulation.inspectionPassed(itemInput);
            boolean resalable = ProductAfterSaleSimulation.resalable(itemInput);
            if (!inspectionPassed && resalable) { throw new IllegalStateException("Failed simulated inspection cannot be resalable"); }
            item.setSimulateInspectionPassed(inspectionPassed); item.setSimulateResalable(resalable);
            item.setStatus("FAILED"); item.setReturnedQuantity(0); item.setRefundedQuantity(0);
            item.setInspectionResult("RECEIVED".equals(line.getFulfillmentStatus()) ? "PENDING" : "NOT_REQUIRED");
            item.setIsResalable(null); items.add(item);
        }
        if (afterSales.insertItems(items) != items.size() || afterSales.markFailed(request.getAfterSaleRequestId(), failureReason(failure)) != 1) {
            throw new IllegalStateException("Accepted after-sale failure details could not be recorded");
        }
    }

    private String failureReason(RuntimeException failure) {
        String code = failure instanceof ProductException product ? product.code().name()
                : "TECHNICAL_FAILURE:" + failure.getClass().getSimpleName();
        return code.length() > 500 ? code.substring(0, 500) : code;
    }
}
