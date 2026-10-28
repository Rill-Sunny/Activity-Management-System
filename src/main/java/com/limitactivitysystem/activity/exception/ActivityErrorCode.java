package com.limitactivitysystem.activity.exception;

import org.springframework.http.HttpStatus;

public enum ActivityErrorCode {
    ACTIVITY_NOT_FOUND(HttpStatus.NOT_FOUND),
    NOT_ACTIVITY_OWNER(HttpStatus.FORBIDDEN),
    ACTIVITY_NOT_DRAFT(HttpStatus.CONFLICT),
    ACTIVITY_ALREADY_PUBLISHED(HttpStatus.CONFLICT),
    INVALID_SESSION_CONFIG(HttpStatus.BAD_REQUEST),
    INVALID_CAPACITY(HttpStatus.BAD_REQUEST),
    INVALID_ACTIVITY_WINDOW(HttpStatus.BAD_REQUEST),
    ZONE_CAPACITY_EXCEEDED(HttpStatus.BAD_REQUEST),
    SEAT_COUNT_MISMATCH(HttpStatus.BAD_REQUEST),
    ACTIVITY_PUBLISH_CONFLICT(HttpStatus.CONFLICT),
    INVALID_ACTIVITY_TYPE(HttpStatus.BAD_REQUEST),
    INVALID_ACTIVITY_REQUEST(HttpStatus.BAD_REQUEST),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    ZONE_NOT_FOUND(HttpStatus.NOT_FOUND),
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND),
    INVALID_ZONE_CONFIG(HttpStatus.BAD_REQUEST),
    INVALID_SEAT_CONFIG(HttpStatus.BAD_REQUEST),
    ZONE_ALREADY_EXISTS(HttpStatus.CONFLICT),
    SEAT_ALREADY_EXISTS(HttpStatus.CONFLICT);

    private final HttpStatus httpStatus;

    ActivityErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
