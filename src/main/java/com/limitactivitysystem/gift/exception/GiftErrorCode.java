package com.limitactivitysystem.gift.exception;

import org.springframework.http.HttpStatus;

public enum GiftErrorCode {
    INVALID_GIFT_REQUEST(HttpStatus.BAD_REQUEST),
    GIFT_ZONE_NOT_FOUND(HttpStatus.NOT_FOUND),
    GIFT_OPERATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    GIFT_ACCESS_DENIED(HttpStatus.FORBIDDEN),
    GIFT_USER_DISABLED(HttpStatus.FORBIDDEN),
    GIFT_SCOPE_INVALID(HttpStatus.CONFLICT),
    GIFT_ZONE_NOT_GIFT(HttpStatus.CONFLICT),
    GIFT_SCOPE_UNAVAILABLE(HttpStatus.CONFLICT),
    GIFT_INSUFFICIENT_INVENTORY(HttpStatus.CONFLICT),
    GIFT_RESOURCE_CONFLICT(HttpStatus.CONFLICT),
    GIFT_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT);
    private final HttpStatus httpStatus;
    GiftErrorCode(HttpStatus httpStatus) { this.httpStatus = httpStatus; }
    public HttpStatus getHttpStatus() { return httpStatus; }
}
