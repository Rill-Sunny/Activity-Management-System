package com.limitactivitysystem.cancellation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.limitactivitysystem.activity.mapper.*;
import com.limitactivitysystem.cache.CacheInvalidationRecorder;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.outbox.OutboxEventWriter;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.util.*;

@Service @Validated
public class CancellationService {
    public record Request(@NotBlank @Size(max=64) String requestId,@NotBlank @Size(max=500) String reason) {
        @JsonAnySetter public void unknown(String key,Object value) {throw new IllegalArgumentException("Unknown field: "+key);}
    }
    private final CurrentUserProvider current; private final AppUserMapper users;
    private final ActivityMapper activities; private final ActivitySessionMapper sessions;
    private final CancellationMapper mapper; private final DatabaseTimeMapper time;
    private final OutboxEventWriter outbox; private final CacheInvalidationRecorder cache;
    public CancellationService(CurrentUserProvider current,AppUserMapper users,ActivityMapper activities,ActivitySessionMapper sessions,
            CancellationMapper mapper,DatabaseTimeMapper time,OutboxEventWriter outbox,CacheInvalidationRecorder cache) {
        this.current=current;this.users=users;this.activities=activities;this.sessions=sessions;
        this.mapper=mapper;this.time=time;this.outbox=outbox;this.cache=cache;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public CancellationMapper.Event cancel(boolean entireActivity,@Positive long target,@Valid @NotNull Request request) {
        long publisher=publisher();
        var discovered=entireActivity?null:sessions.findById(target);
        if(!entireActivity && discovered==null) throw missing();
        long activityId=entireActivity?target:discovered.getActivityId();
        // Activity X also serializes activity-wide ticket-qualified product payments, which need no single Session lock.
        var activity=activities.findByIdForUpdate(activityId);
        if(activity==null) throw missing();
        if(!activity.getPublisherId().equals(publisher)) throw denied();
        var session=entireActivity?null:sessions.findByIdForUpdate(target);
        String type=entireActivity?"ACTIVITY":"SESSION";
        var saved=mapper.byRequest(publisher,request.requestId());
        if(saved!=null) {
            if(!saved.cancelType().equals(type)||saved.targetId()!=target||!saved.reason().equals(request.reason()))
                throw CancellationException.conflict("requestId is already used for different content");
            return saved;
        }
        var now=time.currentTime();
        if(!"PUBLISHED".equals(activity.getStatus())) throw CancellationException.conflict("Activity is not published");
        if(!entireActivity && (session==null||!"SCHEDULED".equals(session.getStatus())||!now.isBefore(session.getEndTime())))
            throw CancellationException.conflict("Only scheduled, unfinished sessions can be cancelled");
        Map<String,Object> row=new HashMap<>(Map.of("cancelType",type,"targetId",target,"activityId",activityId,
                "publisherId",publisher,"reason",request.reason(),"requestId",request.requestId(),"cancelledAt",now));
        mapper.insertEvent(row); long id=((Number)row.get("eventId")).longValue();
        int scoped;
        if(entireActivity) {scoped=mapper.scopeActivity(id,activityId,now); changed(mapper.cancelActivity(activityId));}
        else scoped=mapper.scope(id,target);
        if(mapper.cancelSessions(id)!=scoped)throw new IllegalStateException("Incomplete cancellation lifecycle update");
        mapper.insertBatch(id);
        outbox.append(type+"_CANCELLED",id,Map.of("cancellationEventId",id));
        cache.record("las:activity:"+activityId);
        // Only lifecycle rows are enumerated here; no orders/tickets or resource effects in this transaction.
        for(long sessionId:mapper.sessions(id)) cache.record("las:seat-layout:"+sessionId);
        return mapper.event(id);
    }
    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> detail(long eventId,long after) {
        var event=owned(eventId);
        var batch=mapper.batchForEvent(eventId);
        return Map.of("event",event,"batch",batch,"sessionIds",mapper.sessions(eventId),"items",mapper.items(batch.batchId(),after));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void retry(long eventId,long itemId) {
        owned(eventId); var batch=mapper.batchForEvent(eventId);
        var item=mapper.lockItem(itemId);
        if(item==null||item.batchId()!=batch.batchId()) throw missing();
        mapper.retry(itemId);
    }
    public CancellationMapper.Event owned(long id) {
        long publisher=publisher(); var event=mapper.event(id);
        if(event==null)throw missing(); if(event.publisherId()!=publisher)throw denied();return event;
    }
    private long publisher() {
        long id=current.getAuthenticatedUserId();var user=users.findByIdForShare(id);
        if(user==null||!"ACTIVE".equals(user.getStatus())||!"PUBLISHER".equals(user.getRole()))throw denied();return id;
    }
    static void changed(int count) {if(count!=1) throw new IllegalStateException("Cancellation resource changed unexpectedly");}
    static CancellationException missing(){return new CancellationException("CANCELLATION_NOT_FOUND",HttpStatus.NOT_FOUND,"Cancellation or scope not found");}
    static CancellationException denied(){return new CancellationException("CANCELLATION_ACCESS_DENIED",HttpStatus.FORBIDDEN,"Only the owning publisher can manage cancellation");}
}
