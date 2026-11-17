package com.limitactivitysystem.reschedule;

import com.limitactivitysystem.activity.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RescheduleRetentionService {
    private final RescheduleMapper mapper;
    private final RescheduleService service;
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    public RescheduleRetentionService(RescheduleMapper mapper,RescheduleService service,ActivityMapper activities,ActivitySessionMapper sessions) {
        this.mapper=mapper; this.service=service; this.activities=activities; this.sessions=sessions;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void retain(long eventId,long ticketId) {
        var event=lockLifecycle(eventId);
        if(event!=null && "VALID".equals(mapper.lockTicketStatus(ticketId))) apply(event,ticketId,"DEADLINE_SCAN");
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void retainMessageBatch(long eventId) {
        var event=lockLifecycle(eventId);
        if(event==null) return;
        var due=mapper.due(eventId);
        var valid=new java.util.ArrayList<Long>();
        // Match multi-ticket confirmation: acquire all Ticket locks before any Response write lock.
        // This also avoids a queued refund writer forming a Ticket -> Response -> Ticket cycle.
        for(var row:due) if("VALID".equals(mapper.lockTicketStatus(row.ticketId()))) valid.add(row.ticketId());
        for(long ticketId:valid) apply(event,ticketId,"MQ");
    }
    private RescheduleEvent lockLifecycle(long eventId) {
        var event=service.requireEvent(eventId);
        var discovery=sessions.findById(event.getSessionId());
        if(discovery==null) { throw RescheduleService.missing(); }
        var activity=activities.findByIdForShare(discovery.getActivityId());
        var session=sessions.findByIdForShare(event.getSessionId());
        if(activity==null || session==null) { throw RescheduleService.missing(); }
        // Cancelled lifecycle remains Day 23's responsibility. Never resurrect cancelled/refunded tickets.
        if("CANCELLED".equals(activity.getStatus()) || "CANCELLED".equals(session.getStatus())) { return null; }
        return event;
    }
    private void apply(RescheduleEvent event,long ticketId,String source) {
        String response=mapper.lockResponse(event.getEventId(),ticketId);
        var now=service.now();
        if("PENDING".equals(response) && !now.isBefore(event.getSpecialRefundDeadline()) && mapper.refundClaims(ticketId)==0) {
            RescheduleService.changed(mapper.autoRetain(event.getEventId(),ticketId,now,source));
        }
    }
}
