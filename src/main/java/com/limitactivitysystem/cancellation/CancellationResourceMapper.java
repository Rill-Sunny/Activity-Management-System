package com.limitactivitysystem.cancellation;
import org.apache.ibatis.annotations.*;
import java.util.*;

@Mapper
public interface CancellationResourceMapper {
    @Select("SELECT paid_price FROM ticket WHERE ticket_id=#{id}") java.math.BigDecimal price(long id);
    @Update("UPDATE ticket_order SET status='CLOSED',closed_reason='SESSION_CANCELLED' WHERE order_id=#{id} AND status='PENDING_PAYMENT'") int close(long id);
    @Select("SELECT o.order_id FROM ticket t JOIN ticket_order_item i ON i.order_item_id=t.order_item_id JOIN ticket_order o ON o.order_id=i.order_id WHERE t.ticket_id=#{id}") Long orderOfTicket(long id);
    @Select("SELECT refund_id FROM ticket_refund_request WHERE order_id=#{id} AND refund_policy<>'CANCELLATION_REFUND' AND status IN ('PENDING','FAILED') ORDER BY refund_id") List<Long> supersededRequests(long id);
    @Update("""
        UPDATE ticket_refund_item SET status='FAILED',result='CANCELLATION_SUPERSEDED',
        failure_reason='Superseded by full cancellation refund',processed_at=CURRENT_TIMESTAMP(3)
        WHERE refund_id=#{id} AND status IN ('PENDING','FAILED')
        """) int supersedeItems(long id);
    @Update("""
        UPDATE ticket_refund_request SET status='FAILED',result='CANCELLATION_SUPERSEDED',processed_at=CURRENT_TIMESTAMP(3)
        WHERE refund_id=#{id} AND status IN ('PENDING','FAILED')
        """) int supersedeRequest(long id);
    @Select("SELECT refund_id FROM ticket_refund_item WHERE ticket_id=#{id} AND status='SUCCEEDED'") Long successfulRefund(long id);
    @Select("SELECT COUNT(*) FROM cancellation_batch b JOIN cancellation_scope c ON c.event_id=b.event_id JOIN ticket_order o ON o.session_id=c.session_id WHERE b.event_id=#{event} AND o.order_id=#{order}") int refundScope(long event,long order);
    @Update("UPDATE free_reservation SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP(3) WHERE reservation_id=#{id} AND status='ACTIVE'") int cancelReservation(long id);
    @Select("""
        SELECT o.zone_id,i.seat_id FROM gift_inventory_operation_item i JOIN gift_inventory_operation o ON o.operation_id=i.operation_id WHERE i.operation_item_id=#{id}
        """) Map<String,Object> gift(long id);
    @Update("UPDATE session_seat SET allocation_status='GIFT_CANCELLED' WHERE seat_id=#{id} AND allocation_status='GIFT_ALLOCATED'") int cancelGiftSeat(long id);
    @Update("UPDATE gift_inventory_operation_item SET cancellation_event_id=#{event},cancelled_at=CURRENT_TIMESTAMP(3) WHERE operation_item_id=#{id} AND cancellation_event_id IS NULL") int cancelGift(long id,long event);
    @Select("""
        SELECT (r.status='ACTIVE' AND s.status='SCHEDULED' AND a.status='PUBLISHED' AND CURRENT_TIMESTAMP(3)<s.end_time) effective
        FROM free_reservation r JOIN activity_session s ON s.activity_session_id=r.session_id JOIN activity a ON a.activity_id=s.activity_id
        WHERE r.reservation_id=#{id} AND r.user_id=#{buyer}
        """) Boolean reservationEffective(long id,long buyer);
    @Select("""
        SELECT (i.cancelled_at IS NULL AND s.status='SCHEDULED' AND a.status='PUBLISHED' AND CURRENT_TIMESTAMP(3)<s.end_time) effective
        FROM gift_inventory_operation_item i JOIN gift_inventory_operation o ON o.operation_id=i.operation_id
        JOIN activity_session s ON s.activity_session_id=o.session_id JOIN activity a ON a.activity_id=s.activity_id
        WHERE i.operation_item_id=#{id} AND a.publisher_id=#{publisher}
        """) Boolean giftEffective(long id,long publisher);
}
