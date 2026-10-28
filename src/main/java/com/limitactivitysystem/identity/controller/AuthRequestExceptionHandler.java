package com.limitactivitysystem.identity.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthRequestExceptionHandler {

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ProblemDetail invalidAuthenticationRequest() {
        // Do not include rejected values: validation exceptions may contain the raw password.
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid authentication request");
    }
}
