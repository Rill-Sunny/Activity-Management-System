package com.limitactivitysystem.reservation.exception;

import org.springframework.http.HttpStatus;

public enum ReservationErrorCode {
    INVALID_RESERVATION_REQUEST(HttpStatus.BAD_REQUEST),
    USER_DISABLED(HttpStatus.FORBIDDEN),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    ACTIVITY_NOT_FOUND(HttpStatus.NOT_FOUND),
    ACTIVITY_NOT_FREE(HttpStatus.BAD_REQUEST),
    ACTIVITY_NOT_PUBLISHED(HttpStatus.CONFLICT),
    SESSION_NOT_AVAILABLE(HttpStatus.CONFLICT),
    SESSION_STARTED(HttpStatus.CONFLICT),
    INVALID_SESSION_CONFIG(HttpStatus.CONFLICT),
    RESERVATION_WINDOW_NOT_OPEN(HttpStatus.CONFLICT),
    RESERVATION_WINDOW_CLOSED(HttpStatus.CONFLICT),
    RESERVATION_ALREADY_ACTIVE(HttpStatus.CONFLICT),
    RESERVATION_QUOTA_EXHAUSTED(HttpStatus.CONFLICT),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    RESERVATION_ACCESS_DENIED(HttpStatus.FORBIDDEN),
    RESERVATION_NOT_ACTIVE(HttpStatus.CONFLICT),
    RESERVATION_CONFLICT(HttpStatus.CONFLICT);

    private final HttpStatus httpStatus;
    ReservationErrorCode(HttpStatus httpStatus) { this.httpStatus = httpStatus; }
    public HttpStatus getHttpStatus() { return httpStatus; }
}
