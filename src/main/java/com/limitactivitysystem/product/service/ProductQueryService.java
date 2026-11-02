package com.limitactivitysystem.product.service;

import com.limitactivitysystem.product.dto.ProductResponse;
import com.limitactivitysystem.product.exception.ProductException;
import com.limitactivitysystem.product.mapper.ProductMapper;
import com.limitactivitysystem.product.model.Sku;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.util.List;
import static com.limitactivitysystem.product.exception.ProductErrorCode.*;

@Service
@Validated
@Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
public class ProductQueryService {
    private final ProductScopeService scope;private final ProductMapper products;
    public ProductQueryService(ProductScopeService scope,ProductMapper products){this.scope=scope;this.products=products;}
    public ProductResponse own(@NotNull @Positive Long id){return response(scope.owned(id,false),false);}
    public List<ProductResponse> ownList(){return products.ownedIds(scope.requirePublisher()).stream().map(id -> response(scope.owned(id,false),false)).toList();}
    public ProductResponse get(@NotNull @Positive Long id){return response(requireVisible(id),true);}
    public List<ProductResponse> byActivity(@NotNull @Positive Long id){
        var activity=scope.activity(id);
        if(!List.of("PUBLISHED","FINISHED").contains(activity.getStatus())){return List.of();}
        return products.publicIds(id).stream().map(productId -> response(requireVisible(productId),true)).toList();
    }
    public Sku sku(@NotNull @Positive Long id,@NotNull @Positive Long skuId){
        requireVisible(id);var sku=products.sku(id,skuId);
        if(sku==null || !"ACTIVE".equals(sku.status())){throw new ProductException(SKU_NOT_FOUND,"SKU not found in this product");}return sku;
    }
    public List<Long> members(@NotNull @Positive Long id){scope.owned(id,false);return products.members(id);}
    private ProductScopeService.Scope requireVisible(Long id){var resolved=scope.resolve(id,false);if(!scope.visible(resolved)){throw new ProductException(PRODUCT_NOT_FOUND,"Product is not publicly on sale");}return resolved;}
    private ProductResponse response(ProductScopeService.Scope resolved,boolean publiclyVisible){
        var skus=products.skus(resolved.product().productId());
        return ProductResponse.from(resolved.product(),publiclyVisible ? skus.stream().filter(sku -> "ACTIVE".equals(sku.status())).toList() : skus);
    }
}
