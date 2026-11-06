package com.limitactivitysystem.product.controller;

import com.limitactivitysystem.product.dto.ProductFulfillmentRequest;
import com.limitactivitysystem.product.dto.ProductFulfillmentResponse;
import com.limitactivitysystem.product.service.ProductFulfillmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/publisher/product-orders")
public class PublisherProductOrderController {
    private final ProductFulfillmentService fulfillment;

    public PublisherProductOrderController(ProductFulfillmentService fulfillment) {
        this.fulfillment = fulfillment;
    }

    @PostMapping("/{orderId}/ship")
    public ProductFulfillmentResponse ship(@PathVariable("orderId") @Positive Long orderId,
                                           @Valid @RequestBody ProductFulfillmentRequest request) {
        return fulfillment.ship(orderId, request);
    }
}
