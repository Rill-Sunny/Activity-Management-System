package com.limitactivitysystem.gift.controller;

import com.limitactivitysystem.gift.exception.GiftException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.limitactivitysystem.gift.controller")
public class GiftRequestExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GiftRequestExceptionHandler.class);
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail invalid() { return problem(HttpStatus.BAD_REQUEST, "INVALID_GIFT_REQUEST", "Invalid GIFT allocation request"); }
    @ExceptionHandler(GiftException.class)
    public ProblemDetail rejected(GiftException failure) { return problem(failure.getErrorCode().getHttpStatus(), failure.getErrorCode().name(), failure.getMessage()); }
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail technical(DataAccessException failure) {
        LOG.error("GIFT database operation failed type={}", failure.getClass().getSimpleName());
        return problem(failure instanceof TransientDataAccessException || failure instanceof DataAccessResourceFailureException
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR, "GIFT_TECHNICAL_FAILURE", "GIFT allocation encountered a database failure");
    }
    private ProblemDetail problem(HttpStatus status, String code, String message) {
        var result = ProblemDetail.forStatusAndDetail(status, message); result.setProperty("code", code); return result;
    }
}
