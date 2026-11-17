package com.limitactivitysystem.cancellation;

import com.limitactivitysystem.activity.mapper.*;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.refund.mapper.*;
import com.limitactivitysystem.refund.model.TicketRefundRequest;
import com.limitactivitysystem.refund.service.TicketRefundProcessingService;
import com.limitactivitysystem.reservation.mapper.*;
import com.limitactivitysystem.ticketorder.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.limitactivitysystem.cancellation.CancellationService.changed;

@Service
public class CancellationItemProcessor {
    private final CancellationMapper mapper;private final CancellationResourceMapper resources;
    private final ActivityMapper activities;private final ActivitySessionMapper sessions;private final AppUserMapper users;
    private final TicketOrderMapper orders;private final RefundTicketMapper tickets;private final TicketRefundMapper refunds;
    private final TicketRefundProcessingService processing;private final FreeReservationMapper reservations;private final FreeSessionQuotaMapper quotas;
    private final SessionZoneMapper zones;private final SessionSeatMapper seats;private final DatabaseTimeMapper time;
    public CancellationItemProcessor(CancellationMapper mapper,CancellationResourceMapper resources,ActivityMapper activities,
            ActivitySessionMapper sessions,AppUserMapper users,TicketOrderMapper orders,RefundTicketMapper tickets,TicketRefundMapper refunds,
            TicketRefundProcessingService processing,FreeReservationMapper reservations,FreeSessionQuotaMapper quotas,
            SessionZoneMapper zones,SessionSeatMapper seats,DatabaseTimeMapper time) {
        this.mapper=mapper;this.resources=resources;this.activities=activities;this.sessions=sessions;this.users=users;this.orders=orders;
        this.tickets=tickets;this.refunds=refunds;this.processing=processing;this.reservations=reservations;this.quotas=quotas;
        this.zones=zones;this.seats=seats;this.time=time;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void process(CancellationMapper.Item claim) {
        var item=mapper.lockItem(claim.itemId());
        if(item==null||!"PROCESSING".equals(item.status())||!java.util.Objects.equals(item.claimToken(),claim.claimToken())
                ||!item.leaseUntil().isAfter(time.currentTime()))return;
        var batch=mapper.batchById(item.batchId());
        long event=batch.eventId();
        if(mapper.contains(event,item.sessionId())!=1)throw new IllegalStateException("Task outside cancellation scope");
        Long refund=null;
        switch(item.targetType()) {
            case "ORDER" -> close(item);
            case "TICKET" -> refund=refund(item,event);
            case "RESERVATION" -> reservation(item);
            case "GIFT" -> gift(item,event);
            default -> throw new IllegalStateException("Unknown cancellation target");
        }
        changed(mapper.succeed(item.itemId(),item.claimToken(),refund));
    }
    private void lifecycle(long sessionId) {
        var discovered=sessions.findById(sessionId);
        if(discovered==null)throw new IllegalStateException("Missing cancellation session");
        var activity=activities.findByIdForShare(discovered.getActivityId());var session=sessions.findByIdForShare(sessionId);
        if(activity==null||session==null||!"CANCELLED".equals(session.getStatus()))throw new IllegalStateException("Cancellation gate missing");
    }
    private void close(CancellationMapper.Item item) {
        var order=orders.findByIdForUpdate(item.targetId());
        if(order==null||order.getSessionId()!=item.sessionId())throw new IllegalStateException("Order scope mismatch");
        lifecycle(item.sessionId()); resources.close(item.targetId());
    }
    private Long refund(CancellationMapper.Item item,long event) {
        Long orderId=resources.orderOfTicket(item.targetId());if(orderId==null)throw new IllegalStateException("Missing ticket order");
        var order=orders.findByIdForUpdate(orderId);
        if(order==null||order.getSessionId()!=item.sessionId())throw new IllegalStateException("Ticket scope mismatch");
        if(users.findByIdForShare(order.getBuyerId())==null)throw new IllegalStateException("Missing refund owner");
        lifecycle(item.sessionId());
        var price=resources.price(item.targetId());
        // Order lock serializes every ticket/refund writer. Do not lock Ticket before quota/zone/seat.
        var prior=resources.successfulRefund(item.targetId());if(prior!=null)return prior;
        for(long old:resources.supersededRequests(orderId)) {resources.supersedeItems(old);resources.supersedeRequest(old);}
        var session=sessions.findById(item.sessionId());var activity=activities.findById(session.getActivityId());
        var request=new TicketRefundRequest();request.setBuyerId(order.getBuyerId());request.setOrderId(orderId);
        // The durable Item is the idempotency key. A buyer must not preclaim a predictable internal requestId.
        request.setRequestId("cancel:"+java.util.UUID.randomUUID());request.setReason("SESSION_CANCELLED:"+event);
        request.setSourceEventId(event);request.setRequestedAt(time.currentTime());request.setOpenTimeSnapshot(activity.getOpenTime());
        request.setStartTimeSnapshot(session.getStartTime());
        changed(refunds.insertCancellationRequest(request));
        changed(refunds.insertItem(request.getRefundId(),item.targetId(),price,java.math.BigDecimal.ZERO,price));
        processing.processForCancellation(request.getRefundId(),event);
        return request.getRefundId();
    }
    private void reservation(CancellationMapper.Item item) {
        var discovered=reservations.findById(item.targetId());if(discovered==null)throw new IllegalStateException("Missing reservation");
        users.findByIdForShare(discovered.getUserId());lifecycle(item.sessionId());
        quotas.findBySessionIdForUpdate(item.sessionId());var reservation=reservations.findByIdForUpdate(item.targetId());
        if(reservation==null||reservation.getSessionId()!=item.sessionId())throw new IllegalStateException("Reservation scope mismatch");
        if(resources.cancelReservation(item.targetId())==1)changed(quotas.restoreOne(item.sessionId()));
    }
    private void gift(CancellationMapper.Item item,long event) {
        lifecycle(item.sessionId());var gift=resources.gift(item.targetId());if(gift==null)throw new IllegalStateException("Missing gift");
        long zoneId=((Number)gift.get("zone_id")).longValue(),seatId=((Number)gift.get("seat_id")).longValue();
        var zone=zones.findByIdForUpdate(zoneId);var seat=seats.findByIdForUpdate(seatId);
        if(zone==null||zone.getSessionId()!=item.sessionId()||!"GIFT".equals(zone.getZoneType())||seat==null||!seat.getZoneId().equals(zoneId))
            throw new IllegalStateException("Gift pool mismatch");
        if("GIFT_CANCELLED".equals(seat.getAllocationStatus()))return;
        changed(resources.cancelGiftSeat(seatId));changed(resources.cancelGift(item.targetId(),event));
    }
}
