package com.limitactivitysystem.refund.exception;

public class TicketRefundException extends RuntimeException {
    private final TicketRefundErrorCode errorCode;
    public TicketRefundException(TicketRefundErrorCode errorCode, String message) {
        super(message); this.errorCode = errorCode;
    }
    public TicketRefundErrorCode getErrorCode() { return errorCode; }
}
