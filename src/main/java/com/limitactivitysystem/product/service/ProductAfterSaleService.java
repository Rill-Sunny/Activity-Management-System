package com.limitactivitysystem.product.service;

import com.limitactivitysystem.product.dto.CreateProductAfterSaleRequest;
import com.limitactivitysystem.product.dto.ProductAfterSaleResponse;
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
public class ProductAfterSaleService {
    private final CurrentUserProvider currentUser;
    private final ProductAfterSaleTransactionService transactions;
    private final ProductAfterSaleFailureService failures;
    public ProductAfterSaleService(CurrentUserProvider currentUser, ProductAfterSaleTransactionService transactions,
                                   ProductAfterSaleFailureService failures) {
        this.currentUser = currentUser;
        this.transactions = transactions;
        this.failures = failures;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProductAfterSaleResponse apply(@NotNull @Valid CreateProductAfterSaleRequest request) {
        Long buyerId = currentUser.getAuthenticatedUserId();
        try {
            return transactions.apply(request, buyerId);
        } catch (ProductAfterSaleAcceptedFailure failed) {
            RuntimeException cause = (RuntimeException) failed.getCause();
            try { failures.record(request, buyerId, cause); }
            catch (RuntimeException recordingFailure) { cause.addSuppressed(recordingFailure); }
            throw cause;
        } catch (DuplicateKeyException duplicate) {
            // The failed writer has fully rolled back before the committed idempotency winner is read.
            var replay = transactions.findReplay(request, buyerId);
            if (replay != null) { return replay; }
            throw duplicate;
        }
    }
}
