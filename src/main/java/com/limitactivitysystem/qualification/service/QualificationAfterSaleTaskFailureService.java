package com.limitactivitysystem.qualification.service;

import com.limitactivitysystem.qualification.mapper.QualificationAfterSaleTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QualificationAfterSaleTaskFailureService {
    private final QualificationAfterSaleTaskMapper tasks;
    private final QualificationTelemetry telemetry;

    public QualificationAfterSaleTaskFailureService(QualificationAfterSaleTaskMapper tasks,
            QualificationTelemetry telemetry) {
        this.tasks = tasks;
        this.telemetry = telemetry;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long taskId, Throwable failure) {
        var task = tasks.findContext(taskId);
        if (task == null) { throw new IllegalStateException("Qualification task does not exist: " + taskId); }
        String reason = reason(failure);
        if (tasks.markFailed(taskId, reason) != 1) {
            throw new IllegalStateException("Qualification task failure could not be recorded: " + taskId);
        }
        telemetry.afterSaleFailed(task.getSourceEventId(), task.getTaskId(), task.getProductOrderItemId(), reason);
    }

    private String reason(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) { root = root.getCause(); }
        String message = root.getClass().getSimpleName();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
