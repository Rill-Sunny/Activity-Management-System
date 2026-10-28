package com.limitactivitysystem.whitelist.controller;

import com.limitactivitysystem.whitelist.exception.QualificationDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Applies to future reservation, order and payment controllers as well as whitelist controllers. */
@RestControllerAdvice
public class QualificationExceptionHandler {

    @ExceptionHandler(QualificationDeniedException.class)
    public ProblemDetail qualificationDenied(QualificationDeniedException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
        problem.setProperty("code", QualificationDeniedException.CODE);
        return problem;
    }
}
