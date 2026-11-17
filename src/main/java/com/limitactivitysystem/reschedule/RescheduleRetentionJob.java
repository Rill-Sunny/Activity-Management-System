package com.limitactivitysystem.reschedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="reschedule.retention.enabled",havingValue="true",matchIfMissing=true)
public class RescheduleRetentionJob {
    private static final Logger log=LoggerFactory.getLogger(RescheduleRetentionJob.class);
    private final RescheduleMapper mapper;
    private final RescheduleRetentionService retention;
    public RescheduleRetentionJob(RescheduleMapper mapper,RescheduleRetentionService retention) { this.mapper=mapper; this.retention=retention; }
    @Scheduled(initialDelayString="${reschedule.retention.initial-delay-ms:30000}",fixedDelayString="${reschedule.retention.scan-delay-ms:30000}")
    public void scan() {
        for(var row:mapper.due(null)) {
            try { retention.retain(row.eventId(),row.ticketId()); }
            catch(RuntimeException failure) {
                // The PENDING response is the durable retry record, so the next scan can recover it.
                log.error("Reschedule retention failed eventId={} ticketId={}",row.eventId(),row.ticketId(),failure);
            }
        }
    }
}
