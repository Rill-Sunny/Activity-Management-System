package com.limitactivitysystem.product.controller;

import com.limitactivitysystem.product.dto.CreateProductAfterSaleRequest;
import com.limitactivitysystem.product.dto.ProductAfterSaleResponse;
import com.limitactivitysystem.product.service.ProductAfterSaleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/product-after-sales")
public class ProductAfterSaleController {
    private final ProductAfterSaleService afterSales;

    public ProductAfterSaleController(ProductAfterSaleService afterSales) {
        this.afterSales = afterSales;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductAfterSaleResponse apply(@Valid @RequestBody CreateProductAfterSaleRequest request) {
        return afterSales.apply(request);
    }
}
