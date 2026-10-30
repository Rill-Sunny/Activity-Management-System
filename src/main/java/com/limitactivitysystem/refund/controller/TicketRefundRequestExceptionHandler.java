package com.limitactivitysystem.refund.controller;

import com.limitactivitysystem.refund.exception.TicketRefundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice(basePackages = "com.limitactivitysystem.refund.controller")
public class TicketRefundRequestExceptionHandler {
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ProblemDetail invalidRequest() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid refund request");
        problem.setProperty("code", "INVALID_REFUND_REQUEST"); return problem;
    }
    @ExceptionHandler(TicketRefundException.class)
    public ProblemDetail rejected(TicketRefundException failure) {
        var problem = ProblemDetail.forStatusAndDetail(failure.getErrorCode().getHttpStatus(), failure.getMessage());
        problem.setProperty("code", failure.getErrorCode().name()); return problem;
    }
}
