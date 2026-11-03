package com.limitactivitysystem.product.service;

import com.limitactivitysystem.product.dto.ConfirmProductOrderRequest;
import com.limitactivitysystem.product.dto.ProductOrderResponse;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.product.mapper.ProductOrderMapper;
import com.limitactivitysystem.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashMap;
import java.util.Map;
import static com.limitactivitysystem.product.exception.ProductErrorCode.IDEMPOTENCY_CONFLICT;

@Service
public class ProductOrderReplayService {
    private final CurrentUserProvider currentUser;
    private final ProductOrderMapper orders;

    public ProductOrderReplayService(CurrentUserProvider currentUser, ProductOrderMapper orders) {
        this.currentUser = currentUser;
        this.orders = orders;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public ProductOrderResponse findIfPresent(ConfirmProductOrderRequest request) {
        Long buyerId = currentUser.getAuthenticatedUserId();
        var order = orders.findByBuyerAndRequestId(buyerId, request.getRequestId());
        if (order == null) {
            return null;
        }
        var requested = new HashMap<Long, RequestLine>();
        for (var item : request.getItems()) {
            if (item == null || item.getSkuId() == null || item.getQuantity() == null
                    || requested.put(item.getSkuId(), new RequestLine(item.getQuantity(), item.isAcknowledgeSpecialPolicy())) != null) {
                throw conflict();
            }
        }
        var storedItems = orders.findItems(order.getOrderId());
        Map<Long, RequestLine> stored = new HashMap<>();
        storedItems.forEach(item -> stored.put(item.getSkuId(),
                new RequestLine(item.getQuantity(), item.isSpecialPolicyAcknowledged())));
        if (!requested.equals(stored)) {
            throw conflict();
        }
        return ProductOrderCreationService.response(order, storedItems);
    }

    private ProductException conflict() {
        return new ProductException(IDEMPOTENCY_CONFLICT, "Request ID was already used with a different product list");
    }

    private record RequestLine(Integer quantity, boolean acknowledgeSpecialPolicy) {
    }
}
