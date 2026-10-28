package com.limitactivitysystem.ticketorder.controller;

import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.limitactivitysystem.ticketorder.controller")
public class TicketOrderRequestExceptionHandler {

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class})
    public ProblemDetail invalidOrderRequest() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid ticket order request");
        problem.setProperty("code", "INVALID_TICKET_ORDER_REQUEST");
        return problem;
    }

    @ExceptionHandler(TicketOrderException.class)
    public ProblemDetail orderOperationFailed(TicketOrderException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                exception.getErrorCode().getHttpStatus(), exception.getMessage());
        problem.setProperty("code", exception.getErrorCode().name());
        return problem;
    }
}
