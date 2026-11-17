package com.limitactivitysystem.qualification.schedule;

import com.limitactivitysystem.qualification.service.QualificationAfterSaleService;
import com.limitactivitysystem.qualification.service.QualificationAfterSaleTaskQueryService;
import com.limitactivitysystem.qualification.service.QualificationAfterSaleTaskFailureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class QualificationAfterSaleTaskRetryJob {
    private static final Logger log = LoggerFactory.getLogger(QualificationAfterSaleTaskRetryJob.class);
    private final QualificationAfterSaleTaskQueryService tasks;
    private final QualificationAfterSaleService afterSales;
    private final QualificationAfterSaleTaskFailureService failures;
    private final int batchSize;

    public QualificationAfterSaleTaskRetryJob(QualificationAfterSaleTaskQueryService tasks,
            QualificationAfterSaleService afterSales, QualificationAfterSaleTaskFailureService failures,
            @Value("${qualification.after-sale.retry-batch-size:50}") int batchSize) {
        this.tasks = tasks;
        this.afterSales = afterSales;
        this.failures = failures;
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
    }

    @Scheduled(initialDelayString = "${qualification.after-sale.retry-initial-delay-ms:5000}",
            fixedDelayString = "${qualification.after-sale.retry-delay-ms:5000}")
    public void retryDueTasks() {
        for (Long taskId : tasks.findDueTaskIds(batchSize)) {
            try {
                afterSales.processTask(taskId);
            } catch (RuntimeException failure) {
                try { failures.record(taskId, failure); }
                catch (RuntimeException ledgerFailure) { failure.addSuppressed(ledgerFailure); }
                log.error("Qualification after-sale task failed: taskId={} errorType={}", taskId, failure.getClass().getSimpleName());
            }
        }
    }
}
