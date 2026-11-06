package com.limitactivitysystem.product.service;

import com.limitactivitysystem.cache.CacheAsideService;
import com.limitactivitysystem.product.dto.ProductResponse;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.product.model.Sku;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static com.limitactivitysystem.product.exception.ProductErrorCode.PRODUCT_NOT_FOUND;
import static com.limitactivitysystem.product.exception.ProductErrorCode.SKU_NOT_FOUND;

@Service
@Validated
public class ProductQueryService {
    private final ProductQueryTransactionService database;
    private final CacheAsideService cache;

    public ProductQueryService(ProductQueryTransactionService database, CacheAsideService cache) {
        this.database = database;
        this.cache = cache;
    }

    public ProductResponse own(@NotNull @Positive Long id) {
        return database.own(id);
    }

    public List<ProductResponse> ownList() {
        return database.ownList();
    }

    public ProductResponse get(@NotNull @Positive Long id) {
        ProductResponse result = cache.get("las:product:" + id, ProductResponse.class,
                () -> database.publicDetail(id));
        if (result == null) {
            throw new ProductException(PRODUCT_NOT_FOUND, "Product is not publicly on sale");
        }
        return result;
    }

    public List<ProductResponse> byActivity(@NotNull @Positive Long id) {
        return database.publicIdsByActivity(id).stream().map(this::get).toList();
    }

    public Sku sku(@NotNull @Positive Long id, @NotNull @Positive Long skuId) {
        return get(id).skus().stream().filter(sku -> skuId.equals(sku.skuId())).findFirst()
                .orElseThrow(() -> new ProductException(SKU_NOT_FOUND, "SKU not found in this product"));
    }

    public List<Long> members(@NotNull @Positive Long id) {
        return database.members(id);
    }
}
