package com.limitactivitysystem.reschedule;

import com.limitactivitysystem.messaging.ConsumerProcessMapper;
import com.limitactivitysystem.messaging.ConsumerFailureMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RescheduleAsyncHandler {
    public static final String NOTICE="session-reschedule-notification-v1";
    public static final String RETENTION="reschedule-retention-v1";
    private final ConsumerProcessMapper processed;
    private final ConsumerFailureMapper failures;
    private final RescheduleMapper mapper;
    private final RescheduleService service;
    private final ReschedulePolicy policy;
    private final RescheduleRetentionService retention;
    public RescheduleAsyncHandler(ConsumerProcessMapper processed,ConsumerFailureMapper failures,
            RescheduleMapper mapper,RescheduleService service,ReschedulePolicy policy,RescheduleRetentionService retention) {
        this.processed=processed; this.failures=failures; this.mapper=mapper; this.service=service; this.policy=policy; this.retention=retention;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void notify(String messageId,long eventId) {
        if(processed.begin(NOTICE,messageId)==1) {
            var event=service.requireEvent(eventId);
            String content="场次改期 #"+eventId+"，原时间："+policy.offset(event.getOldStartTime())+" 至 "+policy.offset(event.getOldEndTime())
                    +"；新时间："+policy.offset(event.getNewStartTime())+" 至 "+policy.offset(event.getNewEndTime())
                    +"；特殊全额退款截止："+policy.offset(event.getSpecialRefundDeadline())+"（不含截止时刻）。请查看 /reschedules/"+eventId;
            mapper.notifyBuyers(eventId,messageId,content);
        }
        failures.delete(NOTICE,messageId);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void retain(String messageId,long eventId) {
        service.requireEvent(eventId);
        if(processed.begin(RETENTION,messageId)==1) { retention.retainMessageBatch(eventId); }
        failures.delete(RETENTION,messageId);
    }
}
