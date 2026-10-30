package com.limitactivitysystem.reservation.controller;

import com.limitactivitysystem.reservation.exception.ReservationException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.limitactivitysystem.reservation.controller")
public class ReservationRequestExceptionHandler {
    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class,
            HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail invalidRequest() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid reservation request");
        problem.setProperty("code", "INVALID_RESERVATION_REQUEST"); return problem;
    }

    @ExceptionHandler(ReservationException.class)
    public ProblemDetail rejected(ReservationException failure) {
        var problem = ProblemDetail.forStatusAndDetail(failure.getErrorCode().getHttpStatus(), failure.getMessage());
        problem.setProperty("code", failure.getErrorCode().name()); return problem;
    }
}
