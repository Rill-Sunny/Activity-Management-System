package com.limitactivitysystem.cancellation;
import org.springframework.http.HttpStatus;
public class CancellationException extends RuntimeException {
    private final String code; private final HttpStatus status;
    public CancellationException(String code,HttpStatus status,String message) { super(message); this.code=code; this.status=status; }
    public String code(){return code;} public HttpStatus status(){return status;}
    public static CancellationException conflict(String message) {return new CancellationException("CANCELLATION_CONFLICT",HttpStatus.CONFLICT,message);}
}
