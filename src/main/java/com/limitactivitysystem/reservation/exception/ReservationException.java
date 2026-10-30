package com.limitactivitysystem.reservation.exception;

public class ReservationException extends RuntimeException {
    private final ReservationErrorCode errorCode;
    public ReservationException(ReservationErrorCode errorCode, String message) {
        super(message); this.errorCode = errorCode;
    }
    public ReservationErrorCode getErrorCode() { return errorCode; }
}
