package com.limitactivitysystem.refund.controller;

import com.limitactivitysystem.refund.dto.*;
import com.limitactivitysystem.refund.exception.TicketRefundErrorCode;
import com.limitactivitysystem.refund.exception.TicketRefundException;
import com.limitactivitysystem.refund.service.TicketRefundService;
import com.limitactivitysystem.refund.service.TicketRefundExecutionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class TicketRefundController {
    private final TicketRefundService refunds;
    private final TicketRefundExecutionService processing;
    public TicketRefundController(TicketRefundService refunds, TicketRefundExecutionService processing) {
        this.refunds = refunds; this.processing = processing;
    }
    @PostMapping("/ticket-refunds/{refundId}/process")
    public TicketRefundResponse process(@PathVariable("refundId") @Positive Long refundId,
                                       @RequestBody(required = false) String body) {
        if (body != null && !body.isBlank()) {
            throw new TicketRefundException(TicketRefundErrorCode.INVALID_REFUND_REQUEST,
                    "Refund processing does not accept a request body");
        }
        return processing.process(refundId);
    }
    @PostMapping("/ticket-refunds")
    public ResponseEntity<TicketRefundResponse> submit(@Valid @RequestBody CreateTicketRefundRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(refunds.submit(request));
    }
}
