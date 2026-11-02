package com.limitactivitysystem.product.controller;

import com.limitactivitysystem.product.dto.*;
import com.limitactivitysystem.product.model.Sku;
import com.limitactivitysystem.product.service.*;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class ProductController {
    private final ProductQueryService queries;private final ProductQualificationService qualification;
    public ProductController(ProductQueryService queries,ProductQualificationService qualification){this.queries=queries;this.qualification=qualification;}
    @GetMapping("/products/{productId}") public ProductResponse get(@PathVariable @Positive Long productId){return queries.get(productId);}
    @GetMapping("/activities/{activityId}/products") public List<ProductResponse> byActivity(@PathVariable @Positive Long activityId){return queries.byActivity(activityId);}
    @GetMapping("/products/{productId}/skus/{skuId}")
    public Sku sku(@PathVariable @Positive Long productId,@PathVariable @Positive Long skuId){return queries.sku(productId,skuId);}
    @GetMapping("/products/{productId}/qualification")
    public QualificationResult qualification(@PathVariable @Positive Long productId,@RequestParam(required=false) Long userId){
        if(userId!=null){throw new IllegalArgumentException("Qualification only previews the current account");}
        return qualification.previewCurrent(productId);
    }
}
