package com.limitactivitysystem.reschedule;

import org.springframework.http.HttpStatus;

public class RescheduleException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public RescheduleException(String code, HttpStatus status, String message) {
        super(message); this.code=code; this.status=status;
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
    public static RescheduleException conflict(String code, String message) {
        return new RescheduleException(code,HttpStatus.CONFLICT,message);
    }
}
