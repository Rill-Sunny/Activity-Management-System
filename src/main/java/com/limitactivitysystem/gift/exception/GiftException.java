package com.limitactivitysystem.gift.exception;

public class GiftException extends RuntimeException {
    private final GiftErrorCode errorCode;
    public GiftException(GiftErrorCode errorCode, String message) { super(message); this.errorCode = errorCode; }
    public GiftErrorCode getErrorCode() { return errorCode; }
}
