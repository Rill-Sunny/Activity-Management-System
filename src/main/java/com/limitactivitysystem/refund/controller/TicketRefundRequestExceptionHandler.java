package com.limitactivitysystem.refund.controller;

import com.limitactivitysystem.refund.exception.TicketRefundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice(basePackages = "com.limitactivitysystem.refund.controller")
public class TicketRefundRequestExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(TicketRefundRequestExceptionHandler.class);

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail technicalFailure(DataAccessException failure) {
        var cause = failure.getMostSpecificCause();
        var sql = cause instanceof java.sql.SQLException exception ? exception : null;
        String state = sql == null || sql.getSQLState() == null ? "-" : sql.getSQLState().replaceAll("[^A-Za-z0-9]", "");
        LOG.error("Refund database operation failed type={} sqlState={} vendorCode={}",
                failure.getClass().getSimpleName(), state.substring(0, Math.min(state.length(), 5)), sql == null ? 0 : sql.getErrorCode());
        var status = failure instanceof TransientDataAccessException || failure instanceof DataAccessResourceFailureException
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
        var problem = ProblemDetail.forStatusAndDetail(status, "Refund processing encountered a database failure");
        problem.setProperty("code", "REFUND_TECHNICAL_FAILURE"); return problem;
    }
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
