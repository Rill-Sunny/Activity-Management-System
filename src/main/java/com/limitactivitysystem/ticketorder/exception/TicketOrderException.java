package com.limitactivitysystem.ticketorder.exception;

public class TicketOrderException extends RuntimeException {
    private final TicketOrderErrorCode errorCode;

    public TicketOrderException(TicketOrderErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TicketOrderErrorCode getErrorCode() { return errorCode; }
}
