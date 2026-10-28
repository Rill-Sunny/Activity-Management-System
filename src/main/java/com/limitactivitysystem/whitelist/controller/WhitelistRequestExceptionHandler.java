package com.limitactivitysystem.whitelist.controller;

import com.limitactivitysystem.activity.exception.ActivityException;
import com.limitactivitysystem.whitelist.exception.WhitelistErrorCode;
import com.limitactivitysystem.whitelist.exception.WhitelistException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = PublisherWhitelistController.class)
public class WhitelistRequestExceptionHandler {
    @ExceptionHandler(WhitelistException.class)
    public ProblemDetail whitelistOperationFailed(WhitelistException exception) {
        return problem(exception.getErrorCode().getHttpStatus(), exception.getErrorCode().name(), exception.getMessage());
    }

    @ExceptionHandler(ActivityException.class)
    public ProblemDetail activityOperationFailed(ActivityException exception) {
        return problem(exception.getErrorCode().getHttpStatus(), exception.getErrorCode().name(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ProblemDetail invalidRequest() {
        return problem(HttpStatus.BAD_REQUEST, WhitelistErrorCode.INVALID_WHITELIST_REQUEST.name(),
                "Invalid whitelist request");
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
