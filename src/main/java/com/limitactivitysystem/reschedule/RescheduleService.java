package com.limitactivitysystem.reschedule;

import com.limitactivitysystem.activity.mapper.*;
import com.limitactivitysystem.activity.model.*;
import com.limitactivitysystem.cache.CacheInvalidationRecorder;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.outbox.OutboxEventWriter;
import com.limitactivitysystem.security.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.limitactivitysystem.reschedule.RescheduleException.conflict;

@Service
@Validated
public class RescheduleService {
    private final CurrentUserProvider currentUser;
    private final AppUserMapper users;
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final RescheduleMapper mapper;
    private final ReschedulePolicy policy;
    private final Clock clock;
    private final OutboxEventWriter outbox;
    private final CacheInvalidationRecorder invalidations;

    public RescheduleService(CurrentUserProvider currentUser,AppUserMapper users,ActivityMapper activities,
            ActivitySessionMapper sessions,RescheduleMapper mapper,ReschedulePolicy policy,
            @Qualifier("rescheduleClock") Clock clock,OutboxEventWriter outbox,CacheInvalidationRecorder invalidations) {
        this.currentUser=currentUser; this.users=users; this.activities=activities; this.sessions=sessions;
        this.mapper=mapper; this.policy=policy; this.clock=clock; this.outbox=outbox; this.invalidations=invalidations;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public RescheduleEvent create(@Positive long sessionId,@NotNull @Valid RescheduleRequests.Create input) {
        long operator=currentUser.getAuthenticatedUserId();
        var user=users.findByIdForShare(operator);
        if(user==null || !"ACTIVE".equals(user.getStatus()) || !"PUBLISHER".equals(user.getRole())) { throw denied(); }
        var discovered=requireSession(sessionId);
        var activity=activities.findByIdForShare(discovered.getActivityId());
        if(activity==null || !activity.getPublisherId().equals(operator)) { throw denied(); }
        var session=sessions.findByIdForUpdate(sessionId);
        if(session==null || !activity.getActivityId().equals(session.getActivityId())) { throw missing(); }
        var start=policy.local(input.newStartTime()); var end=policy.local(input.newEndTime());
        var saved=mapper.byRequest(sessionId,input.requestId());
        if(saved!=null) {
            if(!saved.getNewStartTime().equals(start) || !saved.getNewEndTime().equals(end) || !saved.getReason().equals(input.reason())) {
                throw conflict("RESCHEDULE_IDEMPOTENCY_CONFLICT","requestId is already used for different content");
            }
            return saved;
        }
        var now=now();
        requireAvailable(activity,session);
        if(!now.isBefore(session.getStartTime())) { throw conflict("SESSION_ALREADY_STARTED","Started sessions cannot be rescheduled"); }
        if(!start.isBefore(end) || !now.isBefore(start) || start.isBefore(activity.getCloseTime())) {
            throw new RescheduleException("INVALID_RESCHEDULE_TIME",HttpStatus.BAD_REQUEST,
                    "Require future start < end and start >= activity closeTime");
        }
        if(start.equals(session.getStartTime()) && end.equals(session.getEndTime())) {
            throw conflict("RESCHEDULE_UNCHANGED","New session times must differ");
        }
        var event=new RescheduleEvent();
        event.setSessionId(sessionId); event.setRequestId(input.requestId()); event.setOperatorId(operator);
        event.setReason(input.reason()); event.setOldStartTime(session.getStartTime()); event.setOldEndTime(session.getEndTime());
        event.setNewStartTime(start); event.setNewEndTime(end); event.setAnnouncementTime(now);
        event.setSpecialRefundDeadline(policy.deadline(now,start));
        changed(mapper.insert(event));
        int count=mapper.snapshot(event.getEventId(),sessionId);
        if(mapper.initializeResponses(event.getEventId())!=count) { throw new IllegalStateException("Incomplete reschedule responses"); }
        changed(sessions.reschedule(sessionId,start,end));
        outbox.append("SESSION_RESCHEDULED",event.getEventId(),Map.of("rescheduleEventId",event.getEventId()));
        outbox.append("RESCHEDULE_WINDOW_CLOSED",event.getEventId(),Map.of(
                "rescheduleEventId",event.getEventId(),"expiresAt",event.getSpecialRefundDeadline()));
        invalidations.record("las:activity:"+activity.getActivityId());
        invalidations.record("las:seat-layout:"+sessionId);
        return mapper.event(event.getEventId());
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public List<Map<String,Object>> confirm(@Positive long eventId,@NotNull @Valid RescheduleRequests.Confirm input) {
        long buyer=currentUser.getCurrentUserId();
        var event=requireEvent(eventId);
        var discovered=requireSession(event.getSessionId());
        var activity=activities.findByIdForShare(discovered.getActivityId());
        var session=sessions.findByIdForShare(event.getSessionId());
        var ids=uniqueIds(input.ticketIds());
        // Lock every Ticket before any Response; matching auto-retention's order.
        for(long id:ids) {
            requireAffected(eventId,id,buyer);
            String state=mapper.lockTicketStatus(id);
            if(state==null) { throw missing(); }
        }
        for(long id:ids) {
            String response=mapper.lockResponse(eventId,id);
            // Replaying a prior confirmation does not fabricate a fresh response or reopen eligibility.
            if("USER_CONFIRMED".equals(response)) { continue; }
            requireAvailable(activity,session);
            var now=now();
            if(!policy.accepts(event.getAnnouncementTime(),event.getSpecialRefundDeadline(),session.getStartTime(),now)) {
                throw conflict("RESCHEDULE_WINDOW_CLOSED","Confirmation window is closed");
            }
            if(!"VALID".equals(mapper.lockTicketStatus(id)) || mapper.refundClaims(id)>0) {
                throw conflict("RESCHEDULE_TICKET_UNAVAILABLE","Ticket is refunded or has an accepted refund");
            }
            if(!"PENDING".equals(response)) { throw conflict("RESCHEDULE_RESPONSE_FINAL","Automatic retention is already recorded"); }
            changed(mapper.confirm(eventId,id,now));
        }
        return exposedRows(mapper.responses(eventId,buyer));
    }

    public Map<String,Object> detail(@Positive long eventId) {
        long buyer=currentUser.getCurrentUserId();
        var event=requireEvent(eventId);
        var responses=mapper.responses(eventId,buyer);
        if(responses.isEmpty()) { throw denied(); }
        return Map.of("event",view(event),"tickets",exposedRows(responses),"refunds",exposedRows(mapper.refundHistory(eventId,buyer)));
    }
    public List<Map<String,Object>> mine(long beforeId) {
        return mapper.forBuyer(currentUser.getCurrentUserId(),beforeId).stream().map(this::view).toList();
    }
    public List<Map<String,Object>> publisherHistory(@Positive long sessionId,@Positive long beforeId) {
        var user=currentUser.getCurrentActiveUser();
        var session=requireSession(sessionId); var activity=activities.findById(session.getActivityId());
        if(!"PUBLISHER".equals(user.getRole()) || activity==null || !user.getId().equals(activity.getPublisherId())) { throw denied(); }
        return mapper.history(sessionId,beforeId).stream().map(this::view).toList();
    }
    private List<Map<String,Object>> exposedRows(List<Map<String,Object>> rows) {
        return rows.stream().map(row->{
            Map<String,Object> result=new LinkedHashMap<>();
            row.forEach((key,value)->{
                String[] parts=key.split("_"); StringBuilder name=new StringBuilder(parts[0]);
                for(int i=1;i<parts.length;i++) name.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
                if(value instanceof java.sql.Timestamp time) value=policy.offset(time.toLocalDateTime());
                else if(value instanceof LocalDateTime time) value=policy.offset(time);
                result.put(name.toString(),value);
            });
            return result;
        }).toList();
    }
    public Map<String,Object> view(RescheduleEvent event) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("eventId",event.getEventId()); result.put("sessionId",event.getSessionId());
        result.put("oldStartTime",policy.offset(event.getOldStartTime())); result.put("oldEndTime",policy.offset(event.getOldEndTime()));
        result.put("newStartTime",policy.offset(event.getNewStartTime())); result.put("newEndTime",policy.offset(event.getNewEndTime()));
        result.put("reason",event.getReason()); result.put("operatorId",event.getOperatorId());
        result.put("announcementTime",policy.offset(event.getAnnouncementTime()));
        result.put("specialRefundDeadline",policy.offset(event.getSpecialRefundDeadline()));
        return result;
    }
    public RescheduleEvent requireEvent(long eventId) {
        var event=mapper.event(eventId); if(event==null) { throw missing(); } return event;
    }
    public RescheduleMapper.Affected requireAffected(long eventId,long ticketId,long buyerId) {
        var affected=mapper.affected(eventId,ticketId);
        if(affected==null || affected.buyerId()!=buyerId) { throw denied(); } return affected;
    }
    private ActivitySession requireSession(long id) {
        var session=sessions.findById(id); if(session==null) { throw missing(); } return session;
    }
    static void requireAvailable(Activity activity,ActivitySession session) {
        if(activity==null || session==null || !activity.getActivityId().equals(session.getActivityId())
                || !"PUBLISHED".equals(activity.getStatus()) || !"SCHEDULED".equals(session.getStatus())) {
            throw conflict("RESCHEDULE_SESSION_UNAVAILABLE","Session lifecycle does not permit this operation");
        }
    }
    static List<Long> uniqueIds(List<Long> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>100 || ids.stream().anyMatch(id->id==null || id<=0)
                || new HashSet<>(ids).size()!=ids.size()) {
            throw new RescheduleException("INVALID_RESCHEDULE_REQUEST",HttpStatus.BAD_REQUEST,"Require 1..100 distinct positive ticket IDs");
        }
        return ids.stream().sorted().toList();
    }
    LocalDateTime now() { return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS); }
    static RescheduleException denied() { return new RescheduleException("RESCHEDULE_ACCESS_DENIED",HttpStatus.FORBIDDEN,"No access to this reschedule or ticket"); }
    static RescheduleException missing() { return new RescheduleException("RESCHEDULE_NOT_FOUND",HttpStatus.NOT_FOUND,"Reschedule or session not found"); }
    static void changed(int count) { if(count!=1) { throw new IllegalStateException("Reschedule conditional write failed"); } }
}
