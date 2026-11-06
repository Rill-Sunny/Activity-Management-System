package com.limitactivitysystem.product.service;

import com.limitactivitysystem.cache.RedisRequestDeduplicator;
import com.limitactivitysystem.product.dto.ProductOrderResponse;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.product.exception.ProductOrderOutOfStockException;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.ticketorder.service.PaymentRetryBackoff;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static com.limitactivitysystem.product.exception.ProductErrorCode.*;

@Service
@Validated
public class ProductPaymentService {
    private static final int MAX_ATTEMPTS = 3;
    private final CurrentUserProvider currentUser;
    private final ProductPaymentTransactionService payment;
    private final ProductOrderClosureService closing;
    private final PaymentRetryBackoff backoff;
    private final RedisRequestDeduplicator deduplicator;

    public ProductPaymentService(CurrentUserProvider currentUser, ProductPaymentTransactionService payment,
            ProductOrderClosureService closing, PaymentRetryBackoff backoff, RedisRequestDeduplicator deduplicator) {
        this.currentUser = currentUser; this.payment = payment; this.closing = closing; this.backoff = backoff;
        this.deduplicator = deduplicator;
    }

    /** Each attempt crosses a separate REQUIRES_NEW proxy; retries start only after rollback completes. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProductOrderResponse pay(@NotNull @Positive Long orderId) {
        Long buyerId = currentUser.getAuthenticatedUserId();
        try (var lease = deduplicator.beginPayment(orderId, buyerId)) {
            // Redis is only a contention hint. The order row lock and PAID state make duplicate
            // payment calls idempotent, so let concurrent requests serialize in MySQL.
            return executePayment(orderId, buyerId);
        }
    }

    private ProductOrderResponse executePayment(Long orderId, Long buyerId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return payment.payOnce(orderId, buyerId);
            } catch (ProductOrderOutOfStockException shortage) {
                var afterRollback = closing.closeAfterOutOfStock(orderId, buyerId);
                if ("PAID".equals(afterRollback.status())) { return afterRollback; }
                if ("CLOSED".equals(afterRollback.status()) && "OUT_OF_STOCK".equals(afterRollback.closedReason())) { throw shortage; }
                if ("CLOSED".equals(afterRollback.status()) && "EXPIRED".equals(afterRollback.closedReason())) {
                    throw new ProductException(PRODUCT_ORDER_EXPIRED, "Product order payment deadline has passed");
                }
                throw new ProductException(PRODUCT_ORDER_NOT_PENDING, "Product order is no longer pending payment");
            } catch (RuntimeException failure) {
                if (!isRetryableConcurrencyFailure(failure)) { throw failure; }
                if (attempt >= MAX_ATTEMPTS) {
                    throw new ProductException(PRODUCT_PAYMENT_RETRIES_EXHAUSTED,
                            "Product payment could not complete after three database concurrency attempts", failure);
                }
                backoff.pause(attempt);
            }
        }
    }

    private boolean isRetryableConcurrencyFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if (sql.getErrorCode() == 1213 && "40001".equals(sql.getSQLState())) { return true; }
                if (sql.getErrorCode() == 1205 && ("HY000".equals(sql.getSQLState()) || "40001".equals(sql.getSQLState()))) { return true; }
            }
        }
        return false;
    }
}
