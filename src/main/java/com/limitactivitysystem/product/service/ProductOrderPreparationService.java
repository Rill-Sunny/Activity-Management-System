package com.limitactivitysystem.product.service;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.product.dto.ProductOrderItemRequest;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.product.mapper.ProductMapper;
import com.limitactivitysystem.product.mapper.ProductOrderMapper;
import com.limitactivitysystem.product.model.ProductOrderCandidate;
import com.limitactivitysystem.product.model.ProductOrderItem;
import com.limitactivitysystem.product.dto.QualificationResult;
import com.limitactivitysystem.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.limitactivitysystem.product.exception.ProductErrorCode.*;

@Service
public class ProductOrderPreparationService {
    private static final BigDecimal MAX_ORDER_AMOUNT = new BigDecimal("9999999999.99");
    private final CurrentUserProvider currentUser;
    private final AppUserMapper users;
    private final ProductMapper products;
    private final ProductOrderMapper orders;
    private final ProductQualificationService qualification;

    public ProductOrderPreparationService(CurrentUserProvider currentUser, AppUserMapper users,
                                           ProductMapper products, ProductOrderMapper orders,
                                           ProductQualificationService qualification) {
        this.currentUser = currentUser;
        this.users = users;
        this.products = products;
        this.orders = orders;
        this.qualification = qualification;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PreparedOrder prepare(List<ProductOrderItemRequest> requestedItems) {
        if (requestedItems == null || requestedItems.isEmpty() || requestedItems.size() > 100) {
            throw failure(INVALID_PRODUCT_ORDER, "A product order must contain between 1 and 100 items");
        }
        Long buyerId = currentUser.getAuthenticatedUserId();
        Set<Long> seenSkus = new HashSet<>();
        Map<Long, ProductOrderCandidate> candidatesBySku = new HashMap<>();
        Map<Long, QualificationResult> qualificationByProduct = new HashMap<>();
        Map<Long, Long> requestedByProduct = new HashMap<>();
        List<PreparedItem> preparedItems = new ArrayList<>();
        Long publisherId = null;
        BigDecimal total = BigDecimal.ZERO;

        for (ProductOrderItemRequest item : requestedItems) {
            if (item == null || item.getSkuId() == null || item.getSkuId() <= 0
                    || item.getQuantity() == null || item.getQuantity() <= 0) {
                throw failure(INVALID_PRODUCT_ORDER, "SKU IDs and quantities must be positive");
            }
            if (!seenSkus.add(item.getSkuId())) {
                throw failure(DUPLICATE_ORDER_SKU, "A SKU may appear only once in a product order");
            }
            ProductOrderCandidate candidate = products.orderCandidate(item.getSkuId());
            if (candidate == null) {
                throw failure(SKU_NOT_FOUND, "SKU not found");
            }
            if (!"ON_SALE".equals(candidate.productStatus())) {
                throw failure(PRODUCT_NOT_PURCHASABLE, "Product is not on sale");
            }
            if (!"ACTIVE".equals(candidate.skuStatus())) {
                throw failure(SKU_NOT_PURCHASABLE, "SKU is not active");
            }
            if (publisherId == null) {
                publisherId = candidate.publisherId();
            } else if (!publisherId.equals(candidate.publisherId())) {
                throw failure(PRODUCT_PUBLISHER_MISMATCH, "All products in an order must have the same publisher");
            }
            candidatesBySku.put(candidate.skuId(), candidate);
            requestedByProduct.merge(candidate.productId(), item.getQuantity().longValue(), Math::addExact);
            total = total.add(candidate.unitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            preparedItems.add(new PreparedItem(candidate, item));
        }

        if (total.signum() <= 0 || total.compareTo(MAX_ORDER_AMOUNT) > 0) {
            throw failure(PRODUCT_ORDER_AMOUNT_EXCEEDED, "Product order amount exceeds the supported range");
        }
        var publisher = users.findById(publisherId);
        if (publisher == null || !"ACTIVE".equals(publisher.getStatus()) || !"PUBLISHER".equals(publisher.getRole())) {
            throw failure(PRODUCT_NOT_PURCHASABLE, "Product publisher is not active");
        }

        for (PreparedItem prepared : preparedItems) {
            ProductOrderCandidate candidate = prepared.candidate();
            qualificationByProduct.computeIfAbsent(candidate.productId(), productId -> {
                QualificationResult result = qualification.check(buyerId, productId);
                if (!result.allowed()) {
                    throw failure(PRODUCT_QUALIFICATION_DENIED, "Current user is not eligible to buy the product: " + result.reason());
                }
                return result;
            });
        }

        for (Map.Entry<Long, Long> requested : requestedByProduct.entrySet()) {
            ProductOrderCandidate candidate = candidatesBySku.values().stream()
                    .filter(value -> value.productId().equals(requested.getKey())).findFirst().orElseThrow();
            Integer currentCount = orders.findValidCount(buyerId, requested.getKey());
            if (currentCount == null || currentCount < 0
                    || requested.getValue() > (long) candidate.purchaseLimit() - currentCount) {
                throw failure(PRODUCT_PURCHASE_LIMIT_EXCEEDED, "Product purchase limit would be exceeded");
            }
        }
        return new PreparedOrder(buyerId, publisherId, total, List.copyOf(preparedItems));
    }

    private ProductException failure(com.limitactivitysystem.product.exception.ProductErrorCode code, String message) {
        return new ProductException(code, message);
    }

    public record PreparedItem(ProductOrderCandidate candidate, ProductOrderItemRequest request) {
        public BigDecimal subtotal() {
            return candidate.unitPrice().multiply(BigDecimal.valueOf(request.getQuantity()));
        }
        public boolean requiresPolicyAcknowledgement() {
            return "SPECIAL_NO_REASON_RETURN".equals(candidate.refundPolicyTemplate());
        }
    }

    public record PreparedOrder(Long buyerId, Long publisherId, BigDecimal totalAmount, List<PreparedItem> items) {
    }
}
