package com.limitactivitysystem.product.controller;

import com.limitactivitysystem.product.dto.*;
import com.limitactivitysystem.product.model.Sku;
import com.limitactivitysystem.product.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/publisher/products")
public class PublisherProductController {
    private final ProductManagementService management;private final ProductQueryService queries;
    public PublisherProductController(ProductManagementService management,ProductQueryService queries){this.management=management;this.queries=queries;}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@RequestBody @Valid CreateProductRequest request){return management.create(request);}
    @GetMapping public List<ProductResponse> list(){return queries.ownList();}
    @GetMapping("/{productId}") public ProductResponse get(@PathVariable @Positive Long productId){return queries.own(productId);}
    @PostMapping("/{productId}/skus") @ResponseStatus(HttpStatus.CREATED)
    public Sku addSku(@PathVariable @Positive Long productId,@RequestBody @Valid SaveSkuRequest request){return management.addSku(productId,request);}
    @PutMapping("/{productId}/skus/{skuId}")
    public Sku updateSku(@PathVariable @Positive Long productId,@PathVariable @Positive Long skuId,@RequestBody @Valid SaveSkuRequest request){return management.updateSku(productId,skuId,request);}
    @GetMapping("/{productId}/skus") public List<Sku> skus(@PathVariable @Positive Long productId){return queries.own(productId).skus();}
    @PostMapping("/{productId}/publish")
    public ProductResponse publish(@PathVariable @Positive Long productId,@RequestBody(required=false) Map<String,Object> body){requireEmpty(body);return management.publish(productId);}
    @PostMapping("/{productId}/off-sale")
    public ProductResponse takeOffSale(@PathVariable @Positive Long productId,@RequestBody(required=false) Map<String,Object> body){requireEmpty(body);return management.takeOffSale(productId);}
    @PostMapping("/{productId}/whitelist/members") @ResponseStatus(HttpStatus.CREATED)
    public List<Long> addMember(@PathVariable @Positive Long productId,@RequestBody @Valid ProductMemberRequest request){return management.addMember(productId,request.userId());}
    @DeleteMapping("/{productId}/whitelist/members/{userId}")
    public List<Long> removeMember(@PathVariable @Positive Long productId,@PathVariable @Positive Long userId){return management.removeMember(productId,userId);}
    @GetMapping("/{productId}/whitelist/members") public List<Long> members(@PathVariable @Positive Long productId){return queries.members(productId);}
    private void requireEmpty(Map<String,Object> body){if(body!=null && !body.isEmpty()){throw new IllegalArgumentException("This operation accepts no fields");}}
}
