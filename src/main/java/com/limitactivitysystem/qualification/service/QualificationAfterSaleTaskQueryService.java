package com.limitactivitysystem.qualification.service;

import com.limitactivitysystem.qualification.mapper.QualificationAfterSaleTaskMapper;
import com.limitactivitysystem.qualification.model.TicketQualificationAfterSaleTask;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class QualificationAfterSaleTaskQueryService {
    private final QualificationAfterSaleTaskMapper tasks;
    private final QualificationTelemetry telemetry;

    public QualificationAfterSaleTaskQueryService(QualificationAfterSaleTaskMapper tasks,
            QualificationTelemetry telemetry) {
        this.tasks = tasks;
        this.telemetry = telemetry;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public List<Long> findDueTaskIds(int limit) {
        return tasks.findDueTaskIds(limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public TicketQualificationAfterSaleTask get(Long taskId) {
        TicketQualificationAfterSaleTask task = tasks.findById(taskId);
        if (task == null) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Qualification after-sale task not found"); }
        return task;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void replayFailed(Long taskId) {
        TicketQualificationAfterSaleTask task = tasks.findContext(taskId);
        if (task == null || !"FAILED".equals(task.getStatus()) || task.getAttemptCount() < 5) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a task in terminal FAILED state can be replayed");
        }
        if (tasks.resetFailedForReplay(taskId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a task in terminal FAILED state can be replayed");
        }
        telemetry.afterSaleRetry(task.getSourceEventId(), task.getTaskId(),
                task.getProductOrderItemId(), task.getFailureReason());
    }
}
