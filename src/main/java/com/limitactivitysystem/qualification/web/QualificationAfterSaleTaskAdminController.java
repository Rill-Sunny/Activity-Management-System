package com.limitactivitysystem.qualification.web;

import com.limitactivitysystem.qualification.model.TicketQualificationAfterSaleTask;
import com.limitactivitysystem.qualification.service.QualificationAfterSaleTaskQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/ticket-qualification-after-sale-tasks")
public class QualificationAfterSaleTaskAdminController {
    private final QualificationAfterSaleTaskQueryService tasks;

    public QualificationAfterSaleTaskAdminController(QualificationAfterSaleTaskQueryService tasks) {
        this.tasks = tasks;
    }

    @GetMapping("/{taskId}")
    public TaskResponse get(@PathVariable Long taskId) {
        return TaskResponse.from(tasks.get(taskId));
    }

    @PostMapping("/{taskId}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReplayResponse replay(@PathVariable Long taskId) {
        tasks.replayFailed(taskId);
        return new ReplayResponse(taskId, "PENDING");
    }

    public record TaskResponse(Long taskId, String sourceEventId, Long productOrderItemId,
            String qualificationScope, String status, int attemptCount,
            java.time.LocalDateTime nextAttemptAt, String failureReason) {
        static TaskResponse from(TicketQualificationAfterSaleTask task) {
            return new TaskResponse(task.getTaskId(), task.getSourceEventId(), task.getProductOrderItemId(),
                    task.getQualificationScope(), task.getStatus(), task.getAttemptCount(),
                    task.getNextAttemptAt(), task.getFailureReason());
        }
    }

    public record ReplayResponse(Long taskId, String status) { }
}
