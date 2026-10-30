package com.limitactivitysystem.refund.exception;

import org.springframework.http.HttpStatus;

public enum TicketRefundErrorCode {
    INVALID_REFUND_REQUEST(HttpStatus.BAD_REQUEST),
    REFUND_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND),
    REFUND_ACCESS_DENIED(HttpStatus.FORBIDDEN),
    REFUND_USER_DISABLED(HttpStatus.FORBIDDEN),
    REFUND_ORDER_NOT_PAID(HttpStatus.CONFLICT),
    REFUND_TICKET_NOT_FOUND(HttpStatus.NOT_FOUND),
    REFUND_TICKET_NOT_IN_ORDER(HttpStatus.CONFLICT),
    REFUND_TICKET_NOT_VALID(HttpStatus.CONFLICT),
    REFUND_ALREADY_REQUESTED(HttpStatus.CONFLICT),
    REFUND_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    REFUND_SESSION_UNAVAILABLE(HttpStatus.CONFLICT),
    REFUND_SESSION_STARTED(HttpStatus.CONFLICT),
    REFUND_NOT_ALLOWED(HttpStatus.CONFLICT),
    REFUND_WINDOW_CLOSED(HttpStatus.CONFLICT),
    REFUND_AMOUNT_CALCULATION_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;
    TicketRefundErrorCode(HttpStatus httpStatus) { this.httpStatus = httpStatus; }
    public HttpStatus getHttpStatus() { return httpStatus; }
}
