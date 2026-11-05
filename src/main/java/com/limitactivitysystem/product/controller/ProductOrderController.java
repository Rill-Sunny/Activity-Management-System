package com.limitactivitysystem.product.controller;

import com.limitactivitysystem.product.dto.ConfirmProductOrderRequest;
import com.limitactivitysystem.product.dto.PreviewProductOrderRequest;
import com.limitactivitysystem.product.dto.ProductOrderPreviewResponse;
import com.limitactivitysystem.product.dto.ProductOrderResponse;
import com.limitactivitysystem.product.service.ProductOrderPreviewService;
import com.limitactivitysystem.product.service.ProductOrderService;
import com.limitactivitysystem.product.service.ProductPaymentService;
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
    private final ProductPaymentService payments;

    public ProductOrderController(ProductOrderPreviewService previews, ProductOrderService orders, ProductPaymentService payments) {
        this.previews = previews;
        this.orders = orders;
        this.payments = payments;
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

    @PostMapping("/{orderId}/pay")
    public ProductOrderResponse pay(@org.springframework.web.bind.annotation.PathVariable Long orderId) {
        return payments.pay(orderId);
    }
}
