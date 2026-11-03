package com.limitactivitysystem.product.controller;

import com.limitactivitysystem.product.dto.ConfirmProductOrderRequest;
import com.limitactivitysystem.product.dto.PreviewProductOrderRequest;
import com.limitactivitysystem.product.dto.ProductOrderPreviewResponse;
import com.limitactivitysystem.product.dto.ProductOrderResponse;
import com.limitactivitysystem.product.service.ProductOrderPreviewService;
import com.limitactivitysystem.product.service.ProductOrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/product-orders")
public class ProductOrderController {
    private final ProductOrderPreviewService previews;
    private final ProductOrderService orders;

    public ProductOrderController(ProductOrderPreviewService previews, ProductOrderService orders) {
        this.previews = previews;
        this.orders = orders;
    }

    @PostMapping("/preview")
    public ProductOrderPreviewResponse preview(@Valid @RequestBody PreviewProductOrderRequest request) {
        return previews.preview(request);
    }

    @PostMapping("/confirm")
    public ResponseEntity<ProductOrderResponse> confirm(@Valid @RequestBody ConfirmProductOrderRequest request) {
        var result = orders.confirm(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.order());
    }
}
