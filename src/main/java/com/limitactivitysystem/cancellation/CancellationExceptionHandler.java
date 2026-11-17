package com.limitactivitysystem.cancellation;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice(assignableTypes=CancellationController.class)
public class CancellationExceptionHandler {
    @ExceptionHandler(CancellationException.class)
    public ProblemDetail rejected(CancellationException ex) { return problem(ex.status(),ex.code(),ex.getMessage()); }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentNotValidException.class,
            ConstraintViolationException.class,HandlerMethodValidationException.class})
    public ProblemDetail invalid() { return problem(HttpStatus.BAD_REQUEST,"INVALID_CANCELLATION_REQUEST","Invalid cancellation request"); }
    @ExceptionHandler(DuplicateKeyException.class)
    public ProblemDetail duplicate() { return problem(HttpStatus.CONFLICT,"CANCELLATION_CONCURRENT_CONFLICT","A competing request claimed this key or ticket; retry using the same requestId"); }
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail database(DataAccessException ex) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Cancellation database failure type={}",ex.getClass().getSimpleName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE,"CANCELLATION_DATABASE_FAILURE","Database operation failed; retry using the same requestId");
    }
    private ProblemDetail problem(HttpStatus status,String code,String message) {
        var result=ProblemDetail.forStatusAndDetail(status,message); result.setProperty("code",code); return result;
    }
}
