package com.limitactivitysystem.product.service;

import com.limitactivitysystem.product.dto.ConfirmProductOrderRequest;
import com.limitactivitysystem.product.dto.ProductOrderResponse;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.security.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class ProductOrderService {
    private final CurrentUserProvider currentUser;
    private final ProductOrderCreationService creation;
    private final ProductOrderReplayService replay;

    public ProductOrderService(CurrentUserProvider currentUser, ProductOrderCreationService creation,
                               ProductOrderReplayService replay) {
        this.currentUser = currentUser;
        this.creation = creation;
        this.replay = replay;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CreationResult confirm(@NotNull @Valid ConfirmProductOrderRequest request) {
        ProductOrderResponse existing = replay.findIfPresent(request);
        if (existing != null) {
            return new CreationResult(existing, false);
        }
        Long buyerId = currentUser.getAuthenticatedUserId();
        try {
            return new CreationResult(creation.create(request, buyerId), true);
        } catch (DuplicateKeyException | ProductException failure) {
            existing = replay.findIfPresent(request);
            if (existing != null) {
                return new CreationResult(existing, false);
            }
            throw failure;
        }
    }

    public record CreationResult(ProductOrderResponse order, boolean created) {
    }
}
