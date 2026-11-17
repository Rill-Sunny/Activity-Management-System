package com.limitactivitysystem.reschedule;

import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface RescheduleMapper {
    @Insert("""
        INSERT INTO session_reschedule_event(session_id,request_id,old_start_time,old_end_time,
          new_start_time,new_end_time,reason,operator_id,announcement_time,special_refund_deadline)
        VALUES(#{sessionId},#{requestId},#{oldStartTime},#{oldEndTime},#{newStartTime},#{newEndTime},
          #{reason},#{operatorId},#{announcementTime},#{specialRefundDeadline})
        """)
    @Options(useGeneratedKeys=true,keyProperty="eventId")
    int insert(RescheduleEvent event);

    @Select("SELECT * FROM session_reschedule_event WHERE event_id=#{eventId}")
    @Results(id="rescheduleEvent",value={
        @Result(column="event_id",property="eventId",id=true),
        @Result(column="session_id",property="sessionId"),@Result(column="request_id",property="requestId"),
        @Result(column="old_start_time",property="oldStartTime"),@Result(column="old_end_time",property="oldEndTime"),
        @Result(column="new_start_time",property="newStartTime"),@Result(column="new_end_time",property="newEndTime"),
        @Result(column="operator_id",property="operatorId"),@Result(column="announcement_time",property="announcementTime"),
        @Result(column="special_refund_deadline",property="specialRefundDeadline"),@Result(column="created_at",property="createdAt")
    })
    RescheduleEvent event(long eventId);
    @Select("SELECT * FROM session_reschedule_event WHERE session_id=#{sessionId} AND request_id=#{requestId}")
    @ResultMap("rescheduleEvent")
    RescheduleEvent byRequest(@Param("sessionId") long sessionId, @Param("requestId") String requestId);
    @Select("SELECT * FROM session_reschedule_event WHERE session_id=#{sessionId} AND event_id<#{beforeId} ORDER BY event_id DESC LIMIT 100")
    @ResultMap("rescheduleEvent")
    List<RescheduleEvent> history(@Param("sessionId") long sessionId,@Param("beforeId") long beforeId);
    @Select("""
        SELECT e.* FROM session_reschedule_event e
        WHERE EXISTS(SELECT 1 FROM reschedule_affected_ticket a WHERE a.event_id=e.event_id AND a.buyer_id=#{buyerId})
          AND e.event_id < #{beforeId} ORDER BY e.event_id DESC LIMIT 100
        """)
    @ResultMap("rescheduleEvent")
    List<RescheduleEvent> forBuyer(@Param("buyerId") long buyerId, @Param("beforeId") long beforeId);

    // READ COMMITTED + the parent's exclusive Session lock fixes the committed issuance cutoff.
    @Insert("""
        INSERT INTO reschedule_affected_ticket(event_id,ticket_id,buyer_id,order_id,session_id,seat_id,original_paid_amount)
        SELECT #{eventId},t.ticket_id,o.buyer_id,o.order_id,o.session_id,t.seat_id,t.paid_price
        FROM ticket t JOIN ticket_order_item i ON i.order_item_id=t.order_item_id
        JOIN ticket_order o ON o.order_id=i.order_id
        WHERE o.session_id=#{sessionId} AND t.status='VALID' AND o.status IN ('PAID','PARTIALLY_REFUNDED')
        """)
    int snapshot(@Param("eventId") long eventId,@Param("sessionId") long sessionId);
    @Insert("""
        INSERT INTO reschedule_response(event_id,ticket_id)
        SELECT event_id,ticket_id FROM reschedule_affected_ticket WHERE event_id=#{eventId}
        """)
    int initializeResponses(long eventId);
    @Select("""
        SELECT event_id,ticket_id,buyer_id,order_id,session_id,seat_id,original_paid_amount
        FROM reschedule_affected_ticket WHERE event_id=#{eventId} AND ticket_id=#{ticketId}
        """)
    Affected affected(@Param("eventId") long eventId,@Param("ticketId") long ticketId);
    @Select("""
        SELECT a.ticket_id,a.order_id,a.seat_id,a.original_paid_amount,r.status,r.responded_at,r.auto_retained_at,r.source,
          t.status AS ticket_status
        FROM reschedule_affected_ticket a JOIN reschedule_response r USING(event_id,ticket_id)
        JOIN ticket t ON t.ticket_id=a.ticket_id
        WHERE a.event_id=#{eventId} AND a.buyer_id=#{buyerId} ORDER BY a.ticket_id
        """)
    List<Map<String,Object>> responses(@Param("eventId") long eventId,@Param("buyerId") long buyerId);
    @Select("SELECT status FROM ticket WHERE ticket_id=#{ticketId} FOR SHARE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    String lockTicketStatus(long ticketId);
    @Select("SELECT COUNT(*) FROM ticket_refund_item WHERE claimed_ticket_id=#{ticketId}")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    int refundClaims(long ticketId);
    @Select("SELECT status FROM reschedule_response WHERE event_id=#{eventId} AND ticket_id=#{ticketId} FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    String lockResponse(@Param("eventId") long eventId,@Param("ticketId") long ticketId);
    @Update("""
        UPDATE reschedule_response SET status='USER_CONFIRMED',responded_at=#{now},source='USER'
        WHERE event_id=#{eventId} AND ticket_id=#{ticketId} AND status='PENDING'
        """)
    int confirm(@Param("eventId") long eventId,@Param("ticketId") long ticketId,@Param("now") LocalDateTime now);
    @Update("""
        UPDATE reschedule_response SET status='AUTO_RETAINED',auto_retained_at=#{now},source=#{source}
        WHERE event_id=#{eventId} AND ticket_id=#{ticketId} AND status='PENDING'
        """)
    int autoRetain(@Param("eventId") long eventId,@Param("ticketId") long ticketId,
                   @Param("now") LocalDateTime now,@Param("source") String source);
    @Select("""
        SELECT r.event_id,r.ticket_id FROM reschedule_response r
        JOIN session_reschedule_event e ON e.event_id=r.event_id
        JOIN activity_session s ON s.activity_session_id=e.session_id AND s.status<>'CANCELLED'
        JOIN activity a ON a.activity_id=s.activity_id AND a.status<>'CANCELLED'
        JOIN ticket t ON t.ticket_id=r.ticket_id
        WHERE r.status='PENDING' AND t.status='VALID' AND e.special_refund_deadline <= CURRENT_TIMESTAMP(3)
          AND (#{eventId} IS NULL OR e.event_id=#{eventId})
          AND NOT EXISTS(SELECT 1 FROM ticket_refund_item f WHERE f.claimed_ticket_id=r.ticket_id)
        ORDER BY e.special_refund_deadline,r.event_id,r.ticket_id LIMIT 100
        """)
    List<DueResponse> due(@Param("eventId") Long eventId);

    @Select("SELECT submission_id,event_id,buyer_id,request_id,ticket_ids,accepted_at FROM reschedule_refund_submission WHERE buyer_id=#{buyerId} AND request_id=#{requestId}")
    Submission submission(@Param("buyerId") long buyerId,@Param("requestId") String requestId);
    @Insert("""
        INSERT INTO reschedule_refund_submission(event_id,buyer_id,request_id,ticket_ids,accepted_at)
        VALUES(#{eventId},#{buyerId},#{requestId},#{ticketIds},#{acceptedAt})
        """)
    @Options(useGeneratedKeys=true,keyProperty="submissionId")
    int insertSubmission(Map<String,Object> submission);
    @Insert("INSERT INTO reschedule_refund_link(submission_id,refund_id) VALUES(#{submissionId},#{refundId})")
    int link(@Param("submissionId") long submissionId,@Param("refundId") long refundId);
    @Select("SELECT refund_id FROM reschedule_refund_link WHERE submission_id=#{submissionId} ORDER BY refund_id")
    List<Long> refundIds(long submissionId);
    @Select("SELECT refund_id,refund_policy,source_event_id,accepted_at,status,result FROM ticket_refund_request WHERE source_event_id=#{eventId} AND buyer_id=#{buyerId} ORDER BY refund_id")
    List<Map<String,Object>> refundHistory(@Param("eventId") long eventId,@Param("buyerId") long buyerId);
    @Insert("""
        INSERT INTO notification(user_id,event_id,content,status)
        SELECT DISTINCT buyer_id,#{messageId},#{content},'UNREAD'
        FROM reschedule_affected_ticket WHERE event_id=#{eventId}
        """)
    int notifyBuyers(@Param("eventId") long eventId,@Param("messageId") String messageId,@Param("content") String content);

    record Affected(Long eventId,Long ticketId,Long buyerId,Long orderId,Long sessionId,Long seatId,BigDecimal originalPaidAmount) { }
    record DueResponse(Long eventId,Long ticketId) { }
    record Submission(Long submissionId,Long eventId,Long buyerId,String requestId,String ticketIds,LocalDateTime acceptedAt) { }
}
