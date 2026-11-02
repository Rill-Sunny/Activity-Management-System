package com.limitactivitysystem.product.exception;

import org.springframework.http.HttpStatus;

public enum ProductErrorCode {
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND), SKU_NOT_FOUND(HttpStatus.NOT_FOUND),
    PRODUCT_ACCESS_DENIED(HttpStatus.FORBIDDEN), PRODUCT_USER_DISABLED(HttpStatus.FORBIDDEN),
    PRODUCT_INVALID_CONFIGURATION(HttpStatus.BAD_REQUEST), PRODUCT_ACTIVITY_UNAVAILABLE(HttpStatus.CONFLICT),
    PRODUCT_SCOPE_INVALID(HttpStatus.CONFLICT), PRODUCT_NOT_DRAFT(HttpStatus.CONFLICT),
    PRODUCT_NOT_READY(HttpStatus.CONFLICT), PRODUCT_INVALID_STATE(HttpStatus.CONFLICT), SKU_ALREADY_EXISTS(HttpStatus.CONFLICT),
    PRODUCT_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND);
    private final HttpStatus status;
    ProductErrorCode(HttpStatus status) { this.status=status; }
    public HttpStatus status(){ return status; }
}
