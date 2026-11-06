package com.limitactivitysystem.product.service;

import com.limitactivitysystem.product.dto.ProductResponse;
import com.limitactivitysystem.product.mapper.ProductMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Keeps MySQL work behind a separate proxy so cache hits do not open a database transaction. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
public class ProductQueryTransactionService {
    private final ProductScopeService scope;
    private final ProductMapper products;

    public ProductQueryTransactionService(ProductScopeService scope, ProductMapper products) {
        this.scope = scope;
        this.products = products;
    }

    public ProductResponse own(Long id) {
        return response(scope.owned(id, false), false);
    }

    public List<ProductResponse> ownList() {
        return products.ownedIds(scope.requirePublisher()).stream()
                .map(id -> response(scope.owned(id, false), false)).toList();
    }

    public ProductResponse publicDetail(Long id) {
        var resolved = scope.resolveForPublicRead(id);
        return scope.visible(resolved) ? response(resolved, true) : null;
    }

    public List<Long> publicIdsByActivity(Long id) {
        var activity = scope.activity(id);
        if (!List.of("PUBLISHED", "FINISHED").contains(activity.getStatus())) return List.of();
        return products.publicIds(id);
    }

    public List<Long> members(Long id) {
        scope.owned(id, false);
        return products.members(id);
    }

    private ProductResponse response(ProductScopeService.Scope resolved, boolean publiclyVisible) {
        var skus = products.skus(resolved.product().productId());
        return ProductResponse.from(resolved.product(), publiclyVisible
                ? skus.stream().filter(sku -> "ACTIVE".equals(sku.status())).toList()
                : skus);
    }
}
