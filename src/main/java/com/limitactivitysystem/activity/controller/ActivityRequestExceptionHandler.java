package com.limitactivitysystem.activity.controller;

import com.limitactivitysystem.activity.dto.CreateActivityRequest;
import com.limitactivitysystem.activity.dto.CreateActivitySessionRequest;
import com.limitactivitysystem.activity.dto.CreateSessionZoneRequest;
import com.limitactivitysystem.activity.dto.CreateZoneSeatsRequest;
import com.limitactivitysystem.activity.exception.ActivityErrorCode;
import com.limitactivitysystem.activity.exception.ActivityException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {
        PublisherActivityController.class, PublisherActivitySessionController.class,
        PublisherSessionZoneController.class, PublisherZoneSeatController.class, PublisherActivityPublishController.class,
        ActivityQueryController.class
})
public class ActivityRequestExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalidActivityRequest(MethodArgumentNotValidException exception) {
        var errors = exception.getBindingResult();
        ActivityErrorCode code;
        if (errors.hasFieldErrors("capacity") || errors.hasFieldErrors("totalAmount")) {
            code = ActivityErrorCode.INVALID_CAPACITY;
        } else if (errors.getTarget() instanceof CreateActivityRequest
                && (errors.hasFieldErrors("openTime") || errors.hasFieldErrors("closeTime"))) {
            code = ActivityErrorCode.INVALID_ACTIVITY_WINDOW;
        } else if (errors.getTarget() instanceof CreateActivityRequest && errors.hasFieldErrors("type")) {
            code = ActivityErrorCode.INVALID_ACTIVITY_TYPE;
        } else if (errors.getTarget() instanceof CreateActivitySessionRequest) {
            code = ActivityErrorCode.INVALID_SESSION_CONFIG;
        } else if (errors.getTarget() instanceof CreateSessionZoneRequest) {
            code = ActivityErrorCode.INVALID_ZONE_CONFIG;
        } else if (errors.getTarget() instanceof CreateZoneSeatsRequest) {
            code = ActivityErrorCode.INVALID_SEAT_CONFIG;
        } else {
            code = ActivityErrorCode.INVALID_ACTIVITY_REQUEST;
        }
        return problem(code, "Invalid activity request");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail unreadableActivityRequest() {
        return problem(ActivityErrorCode.INVALID_ACTIVITY_REQUEST, "Invalid activity request");
    }

    @ExceptionHandler(ActivityException.class)
    public ProblemDetail activityOperationFailed(ActivityException exception) {
        return problem(exception.getErrorCode(), exception.getMessage());
    }

    private ProblemDetail problem(ActivityErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.getHttpStatus(), detail);
        problem.setProperty("code", code.name());
        return problem;
    }
}
