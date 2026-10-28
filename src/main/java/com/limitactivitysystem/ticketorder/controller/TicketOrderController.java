package com.limitactivitysystem.ticketorder.controller;

import com.limitactivitysystem.ticketorder.dto.CreateTicketOrderRequest;
import com.limitactivitysystem.ticketorder.dto.TicketOrderResponse;
import com.limitactivitysystem.ticketorder.dto.TicketPaymentResponse;
import com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.service.TicketPaymentService;
import com.limitactivitysystem.ticketorder.service.TicketOrderQueryService;
import com.limitactivitysystem.ticketorder.service.TicketOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/ticket-orders")
public class TicketOrderController {
    private final TicketOrderService orders;
    private final TicketOrderQueryService queries;
    private final TicketPaymentService payments;

    public TicketOrderController(TicketOrderService orders, TicketOrderQueryService queries, TicketPaymentService payments) {
        this.orders = orders;
        this.queries = queries;
        this.payments = payments;
    }

    @PostMapping
    public ResponseEntity<TicketOrderResponse> create(@Valid @RequestBody CreateTicketOrderRequest request) {
        var result = orders.createOrder(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.order());
    }

    @GetMapping("/{orderId}")
    public TicketOrderResponse getOwnOrder(@PathVariable("orderId") @Positive Long orderId) {
        return queries.getOwnOrder(orderId);
    }

    @PostMapping("/{orderId}/pay")
    public TicketPaymentResponse pay(@PathVariable("orderId") @Positive Long orderId,
                                      @RequestBody(required = false) Map<String, Object> body) {
        if (body != null && !body.isEmpty()) {
            throw new TicketOrderException(TicketOrderErrorCode.INVALID_PAYMENT_REQUEST, "Payment accepts no client-supplied fields");
        }
        return payments.pay(orderId);
    }
}
