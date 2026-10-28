package com.limitactivitysystem.whitelist.exception;

import org.springframework.http.HttpStatus;

public enum WhitelistErrorCode {
    WHITELIST_NOT_CONFIGURED(HttpStatus.NOT_FOUND),
    WHITELIST_MEMBER_ALREADY_EXISTS(HttpStatus.CONFLICT),
    WHITELIST_USER_NOT_FOUND(HttpStatus.NOT_FOUND),
    WHITELIST_NOT_EDITABLE(HttpStatus.CONFLICT),
    INVALID_WHITELIST_REQUEST(HttpStatus.BAD_REQUEST);

    private final HttpStatus httpStatus;

    WhitelistErrorCode(HttpStatus httpStatus) { this.httpStatus = httpStatus; }
    public HttpStatus getHttpStatus() { return httpStatus; }
}
