package com.limitactivitysystem.refund.controller;

import com.limitactivitysystem.refund.dto.*;
import com.limitactivitysystem.refund.service.TicketRefundService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class TicketRefundController {
    private final TicketRefundService refunds;
    public TicketRefundController(TicketRefundService refunds) { this.refunds = refunds; }
    @PostMapping("/ticket-refunds")
    public ResponseEntity<TicketRefundResponse> submit(@Valid @RequestBody CreateTicketRefundRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(refunds.submit(request));
    }
}
