package com.limitactivitysystem.product.service;

import com.limitactivitysystem.product.dto.ConfirmProductOrderRequest;
import com.limitactivitysystem.product.dto.ProductOrderResponse;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.product.mapper.ProductOrderMapper;
import com.limitactivitysystem.product.model.ProductOrder;
import com.limitactivitysystem.product.model.ProductOrderItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import java.util.ArrayList;
import java.util.List;
import static com.limitactivitysystem.product.exception.ProductErrorCode.*;

@Service
@Validated
public class ProductOrderCreationService {
    private final ProductOrderPreparationService preparation;
    private final ProductOrderPreviewService previews;
    private final ProductOrderMapper orders;

    public ProductOrderCreationService(ProductOrderPreparationService preparation,
                                       ProductOrderPreviewService previews, ProductOrderMapper orders) {
        this.preparation = preparation;
        this.previews = previews;
        this.orders = orders;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public ProductOrderResponse create(@NotNull @Valid ConfirmProductOrderRequest request, Long buyerId) {
        var prepared = preparation.prepare(request.getItems());
        if (!prepared.buyerId().equals(buyerId)) {
            throw new ProductException(PRODUCT_ORDER_CREATION_CONFLICT, "Authenticated buyer changed during order creation");
        }
        previews.requireStock(prepared);

        List<ProductOrderItem> items = new ArrayList<>();
        for (var line : prepared.items()) {
            boolean special = line.requiresPolicyAcknowledgement();
            if (special && !line.request().isAcknowledgeSpecialPolicy()) {
                throw new ProductException(PRODUCT_POLICY_ACK_REQUIRED, "The special return policy must be acknowledged");
            }
            if (!special && line.request().isAcknowledgeSpecialPolicy()) {
                throw new ProductException(INVALID_PRODUCT_ORDER, "Acknowledgement is only accepted for the special policy");
            }
            var candidate = line.candidate();
            ProductOrderItem item = new ProductOrderItem();
            item.setProductId(candidate.productId());
            item.setSkuId(candidate.skuId());
            item.setQuantity(line.request().getQuantity());
            item.setUnitPrice(candidate.unitPrice());
            item.setProductNameSnapshot(candidate.productName());
            item.setSkuNameSnapshot(candidate.skuName());
            item.setActivityIdSnapshot(candidate.activityId());
            item.setQualificationTypeSnapshot(candidate.qualificationType());
            item.setQualificationScopeSnapshot(candidate.qualificationScope());
            item.setQualificationSessionIdSnapshot(candidate.qualificationSessionId());
            item.setRefundPolicyTemplateSnapshot(candidate.refundPolicyTemplate());
            item.setRefundPolicyVersionSnapshot(candidate.refundPolicyVersion());
            item.setSpecialPolicyAcknowledged(special);
            items.add(item);
        }

        ProductOrder order = new ProductOrder();
        order.setBuyerId(prepared.buyerId());
        order.setPublisherId(prepared.publisherId());
        order.setRequestId(request.getRequestId());
        order.setTotalAmount(prepared.totalAmount());
        if (orders.insert(order) != 1 || order.getOrderId() == null) {
            throw new ProductException(PRODUCT_ORDER_CREATION_CONFLICT, "Product order header was not created");
        }
        for (ProductOrderItem item : items) {
            item.setOrderId(order.getOrderId());
        }
        if (orders.insertItems(items) != items.size()) {
            throw new ProductException(PRODUCT_ORDER_CREATION_CONFLICT, "Not all product order items were saved");
        }
        ProductOrder stored = orders.findById(order.getOrderId());
        return response(stored, orders.findItems(order.getOrderId()));
    }

    static ProductOrderResponse response(ProductOrder order, List<ProductOrderItem> items) {
        return new ProductOrderResponse(order.getOrderId(), order.getRequestId(), order.getPublisherId(),
                order.getTotalAmount(), order.getStatus(), order.getCreatedAt(), order.getExpiresAt(), order.getPaidAt(), order.getClosedReason(),
                items.stream().map(item -> new ProductOrderResponse.Item(item.getProductId(), item.getSkuId(),
                        item.getProductNameSnapshot(), item.getSkuNameSnapshot(), item.getQuantity(),
                        item.getUnitPrice(), item.getUnitPrice().multiply(java.math.BigDecimal.valueOf(item.getQuantity())),
                        item.getActivityIdSnapshot(), item.getQualificationTypeSnapshot(),
                        item.getQualificationScopeSnapshot(), item.getQualificationSessionIdSnapshot(),
                        item.getRefundPolicyTemplateSnapshot(), item.getRefundPolicyVersionSnapshot(),
                        item.isSpecialPolicyAcknowledged())).toList());
    }
}
