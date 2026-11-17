package com.limitactivitysystem.reschedule;

import com.limitactivitysystem.activity.mapper.*;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.refund.dto.TicketRefundResponse;
import com.limitactivitysystem.refund.mapper.*;
import com.limitactivitysystem.refund.service.TicketRefundApplicationService;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.model.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.time.LocalDateTime;
import java.util.*;
import static com.limitactivitysystem.reschedule.RescheduleService.*;
import static com.limitactivitysystem.reschedule.RescheduleException.conflict;

@Service
@Validated
public class RescheduleRefundService {
    private final CurrentUserProvider currentUser;
    private final RescheduleService service;
    private final RescheduleMapper mapper;
    private final ReschedulePolicy policy;
    private final TicketOrderMapper orders;
    private final AppUserMapper users;
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final RefundTicketMapper tickets;
    private final TicketRefundMapper refunds;
    private final TicketRefundApplicationService applications;
    public RescheduleRefundService(CurrentUserProvider currentUser,RescheduleService service,RescheduleMapper mapper,
            ReschedulePolicy policy,TicketOrderMapper orders,AppUserMapper users,ActivityMapper activities,
            ActivitySessionMapper sessions,RefundTicketMapper tickets,TicketRefundMapper refunds,TicketRefundApplicationService applications) {
        this.currentUser=currentUser; this.service=service; this.mapper=mapper; this.policy=policy; this.orders=orders;
        this.users=users; this.activities=activities; this.sessions=sessions; this.tickets=tickets; this.refunds=refunds; this.applications=applications;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public Result submit(@Positive long eventId,@NotNull @Valid RescheduleRequests.Refund input) {
        long buyer=currentUser.getCurrentUserId();
        var ids=uniqueIds(input.ticketIds());
        String canonical=ids.toString();
        var event=service.requireEvent(eventId);
        var affected=ids.stream().map(id->service.requireAffected(eventId,id,buyer)).toList();
        var orderIds=affected.stream().map(RescheduleMapper.Affected::orderId).distinct().sorted().toList();
        List<TicketOrder> lockedOrders=new ArrayList<>();
        for(long id:orderIds) {
            var order=orders.findByIdForUpdate(id);
            if(order==null || !order.getBuyerId().equals(buyer) || !order.getSessionId().equals(event.getSessionId())) { throw denied(); }
            lockedOrders.add(order);
        }
        var user=users.findByIdForShare(buyer);
        if(user==null || !"ACTIVE".equals(user.getStatus())) { throw denied(); }
        var existing=mapper.submission(buyer,input.requestId());
        if(existing!=null) {
            if(existing.eventId()!=eventId || !existing.ticketIds().equals(canonical)) {
                throw conflict("RESCHEDULE_IDEMPOTENCY_CONFLICT","Refund requestId is already used for different content");
            }
            return result(existing.submissionId(),existing.eventId(),existing.acceptedAt());
        }
        for(var order:lockedOrders) {
            if(!List.of("PAID","PARTIALLY_REFUNDED").contains(order.getStatus())) {
                throw conflict("REFUND_ORDER_NOT_PAID","Refund requires a paid order");
            }
        }
        var discovery=sessions.findById(event.getSessionId());
        if(discovery==null) { throw missing(); }
        var activity=activities.findByIdForShare(discovery.getActivityId());
        var session=sessions.findByIdForShare(event.getSessionId());
        requireAvailable(activity,session);
        Map<Long,List<Ticket>> byOrder=new TreeMap<>();
        for(var snapshot:affected) {
            var ticket=tickets.lockTicket(snapshot.ticketId());
            var ownership=tickets.findOwnership(snapshot.ticketId());
            if(ticket==null || ownership==null || !ownership.orderId().equals(snapshot.orderId())
                    || !ownership.buyerId().equals(buyer) || !ticket.getSeatId().equals(snapshot.seatId())
                    || ticket.getPaidPrice().compareTo(snapshot.originalPaidAmount())!=0) {
                throw conflict("RESCHEDULE_SNAPSHOT_CONFLICT","Ticket no longer matches its immutable snapshot");
            }
            if(!"VALID".equals(ticket.getStatus()) || mapper.refundClaims(snapshot.ticketId())>0) {
                throw conflict("REFUND_ALREADY_REQUESTED","Ticket already has an accepted or successful refund");
            }
            byOrder.computeIfAbsent(snapshot.orderId(),ignored->new ArrayList<>()).add(ticket);
        }
        var now=service.now();
        if(!policy.accepts(event.getAnnouncementTime(),event.getSpecialRefundDeadline(),session.getStartTime(),now)) {
            throw conflict("RESCHEDULE_WINDOW_CLOSED","Special refund window is closed");
        }
        var submission=new HashMap<String,Object>();
        submission.put("eventId",eventId); submission.put("buyerId",buyer); submission.put("requestId",input.requestId());
        submission.put("ticketIds",canonical); submission.put("acceptedAt",now);
        changed(mapper.insertSubmission(submission));
        long submissionId=((Number)submission.get("submissionId")).longValue();
        for(var entry:byOrder.entrySet()) {
            var accepted=applications.saveRescheduleQuote(buyer,entry.getKey(),"reschedule-"+submissionId+"-"+entry.getKey(),
                    eventId,now,activity.getOpenTime(),session.getStartTime(),entry.getValue());
            changed(mapper.link(submissionId,accepted.refundId()));
        }
        return result(submissionId,eventId,now);
    }
    private Result result(long submissionId,long eventId,LocalDateTime acceptedAt) {
        var accepted=mapper.refundIds(submissionId).stream().map(id->{
            var saved=refunds.findById(id);
            return new TicketRefundResponse(saved.getRefundId(),saved.getBuyerId(),saved.getOrderId(),saved.getRequestId(),
                    saved.getReason(),saved.getRequestedAt(),saved.getStatus(),saved.getCreatedAt(),saved.getProcessedAt(),
                    saved.getResult(),refunds.findItems(id));
        }).toList();
        return new Result(submissionId,eventId,policy.offset(acceptedAt),"SESSION_RESCHEDULE_REFUND",accepted);
    }
    public record Result(long submissionId,long eventId,java.time.OffsetDateTime acceptedAt,String refundPolicy,List<TicketRefundResponse> refunds) { }
}
