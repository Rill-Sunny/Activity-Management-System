package com.limitactivitysystem.whitelist.exception;

public class WhitelistException extends RuntimeException {
    private final WhitelistErrorCode errorCode;

    public WhitelistException(WhitelistErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public WhitelistErrorCode getErrorCode() { return errorCode; }
}
