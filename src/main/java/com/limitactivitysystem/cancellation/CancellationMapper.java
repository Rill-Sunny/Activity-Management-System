package com.limitactivitysystem.cancellation;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.*;

@Mapper
public interface CancellationMapper {
    record Event(long eventId,String cancelType,long targetId,long activityId,long publisherId,long operatorId,
                 String reason,LocalDateTime cancelledAt,String requestId) { }
    record Batch(long batchId,long eventId,String status,int scanPhase,long nextCursor,int totalItems,
                 int succeededItems,int failedItems,LocalDateTime startedAt,LocalDateTime completedAt) { }
    record Item(long itemId,long batchId,String targetType,long targetId,long sessionId,String action,String status,
                int attemptCount,int retryCount,LocalDateTime nextAttemptAt,LocalDateTime leaseUntil,String claimToken,
                String failureReason,LocalDateTime processedAt,Long refundId) { }
    record Target(long targetId,long sessionId) { }
    String EVENT="SELECT event_id,cancel_type,target_id,activity_id,publisher_id,operator_id,reason,cancelled_at,request_id FROM cancellation_event ";
    @Select(EVENT+"WHERE event_id=#{id}") Event event(long id);
    @Select(EVENT+"WHERE publisher_id=#{publisher} AND request_id=#{request}") Event byRequest(long publisher,String request);
    @Insert("""
        INSERT INTO cancellation_event(cancel_type,target_id,activity_id,publisher_id,operator_id,reason,cancelled_at,request_id)
        VALUES(#{cancelType},#{targetId},#{activityId},#{publisherId},#{publisherId},#{reason},#{cancelledAt},#{requestId})
        """)
    @Options(useGeneratedKeys=true,keyProperty="eventId") int insertEvent(Map<String,Object> row);
    @Insert("INSERT INTO cancellation_scope(event_id,session_id) VALUES(#{eventId},#{sessionId})") int scope(long eventId,long sessionId);
    @Insert("""
        INSERT INTO cancellation_scope(event_id,session_id)
        SELECT #{eventId},activity_session_id FROM activity_session
        WHERE activity_id=#{activityId} AND status='SCHEDULED' AND end_time>#{at}
        """) int scopeActivity(long eventId,long activityId,LocalDateTime at);
    @Update("UPDATE activity SET status='CANCELLED' WHERE activity_id=#{id} AND status='PUBLISHED'") int cancelActivity(long id);
    @Update("""
        UPDATE activity_session s JOIN cancellation_scope c ON c.session_id=s.activity_session_id
        SET s.status='CANCELLED' WHERE c.event_id=#{id} AND s.status='SCHEDULED'
        """) int cancelSessions(long id);
    @Select("SELECT session_id FROM cancellation_scope WHERE event_id=#{id} ORDER BY session_id") List<Long> sessions(long id);
    @Select("SELECT COUNT(*) FROM cancellation_scope WHERE event_id=#{eventId} AND session_id=#{sessionId}") int contains(long eventId,long sessionId);
    @Insert("INSERT INTO cancellation_batch(event_id) VALUES(#{id})") int insertBatch(long id);
    String BATCH="SELECT batch_id,event_id,status,scan_phase,next_cursor,total_items,succeeded_items,failed_items,started_at,completed_at FROM cancellation_batch ";
    @Select(BATCH+"WHERE event_id=#{id}") Batch batchForEvent(long id);
    @Select(BATCH+"WHERE batch_id=#{id}") Batch batchById(long id);
    @Select("SELECT batch_id FROM cancellation_batch WHERE scan_phase=4 AND status='PROCESSING' ORDER BY batch_id LIMIT 50") List<Long> unfinishedSummaries();
    @Select(BATCH+"WHERE batch_id=#{id} FOR UPDATE")
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false) Batch lockBatch(long id);
    @Select("SELECT batch_id FROM cancellation_batch WHERE scan_phase<4 ORDER BY batch_id LIMIT 50") List<Long> activeBatches();
    @Update("""
        UPDATE cancellation_batch SET scan_phase=#{phase},next_cursor=#{cursor},status='PROCESSING',
            started_at=COALESCE(started_at,CURRENT_TIMESTAMP(3)),total_items=total_items+#{added}
        WHERE batch_id=#{id}
        """) int checkpoint(long id,int phase,long cursor,int added);
    @Select("""
        <script>
        SELECT x.target_id,x.session_id FROM (
        <choose>
          <when test="phase == 0">SELECT order_id target_id,session_id FROM ticket_order</when>
          <when test="phase == 1">SELECT t.ticket_id target_id,o.session_id FROM ticket t JOIN ticket_order_item i ON i.order_item_id=t.order_item_id JOIN ticket_order o ON o.order_id=i.order_id</when>
          <when test="phase == 2">SELECT reservation_id target_id,session_id FROM free_reservation</when>
          <otherwise>SELECT i.operation_item_id target_id,o.session_id FROM gift_inventory_operation_item i JOIN gift_inventory_operation o ON o.operation_id=i.operation_id</otherwise>
        </choose>
        ) x JOIN cancellation_scope c ON c.session_id=x.session_id AND c.event_id=#{eventId}
        WHERE x.target_id &gt; #{cursor} ORDER BY x.target_id LIMIT 50
        </script>
        """) List<Target> page(long eventId,int phase,long cursor);
    @Insert("""
        INSERT INTO cancellation_batch_item(batch_id,target_type,target_id,session_id,action)
        VALUES(#{batch},#{type},#{target},#{session},#{action})
        ON DUPLICATE KEY UPDATE item_id=item_id
        """) int insertItem(long batch,String type,long target,long session,String action);
    String ITEM="SELECT item_id,batch_id,target_type,target_id,session_id,action,status,attempt_count,retry_count,next_attempt_at,lease_until,claim_token,failure_reason,processed_at,refund_id FROM cancellation_batch_item ";
    @Select(ITEM+"WHERE item_id=#{id} FOR UPDATE")
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false) Item lockItem(long id);
    @Select(ITEM+"WHERE batch_id=#{batch} AND item_id>#{after} ORDER BY item_id LIMIT 100") List<Item> items(long batch,long after);
    @Select(ITEM+"WHERE ((status IN ('PENDING','RETRY_PENDING') AND next_attempt_at<=CURRENT_TIMESTAMP(3)) OR (status='PROCESSING' AND lease_until<CURRENT_TIMESTAMP(3))) ORDER BY item_id LIMIT 1 FOR UPDATE SKIP LOCKED")
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false) Item next();
    @Update("""
        UPDATE cancellation_batch_item SET status='PROCESSING',attempt_count=attempt_count+1,retry_count=retry_count+1,
            claim_token=#{token},lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)) WHERE item_id=#{id}
        """) int claim(long id,String token);
    @Update("""
        UPDATE cancellation_batch_item SET status='SUCCEEDED',claim_token=NULL,lease_until=NULL,
            processed_at=CURRENT_TIMESTAMP(3),refund_id=#{refundId} WHERE item_id=#{id} AND status='PROCESSING' AND claim_token=#{token}
        """) int succeed(long id,String token,Long refundId);
    @Update("""
        UPDATE cancellation_batch_item SET status=#{status},failure_reason=#{reason},claim_token=NULL,lease_until=NULL,
            next_attempt_at=TIMESTAMPADD(SECOND,10,CURRENT_TIMESTAMP(3)) WHERE item_id=#{id} AND status='PROCESSING' AND claim_token=#{token}
        """) int fail(long id,String token,String status,String reason);
    @Update("""
        UPDATE cancellation_batch_item SET status='PENDING',retry_count=0,next_attempt_at=CURRENT_TIMESTAMP(3)
        WHERE item_id=#{id} AND status IN ('FAILED','RETRY_PENDING')
        """) int retry(long id);
    @Select("""
        SELECT COUNT(*) total,COALESCE(SUM(status='SUCCEEDED'),0) succeeded,COALESCE(SUM(status='FAILED'),0) failed
        FROM cancellation_batch_item WHERE batch_id=#{id}
        """) Map<String,Object> counts(long id);
    @Update("""
        UPDATE cancellation_batch SET status=#{status},total_items=#{total},succeeded_items=#{succeeded},failed_items=#{failed},
            completed_at=CASE WHEN #{status}='COMPLETED' THEN COALESCE(completed_at,CURRENT_TIMESTAMP(3)) ELSE NULL END
        WHERE batch_id=#{id}
        """) int summarize(long id,String status,int total,int succeeded,int failed);
    @Select("""
        SELECT e.event_id,e.cancel_type,e.target_id,e.reason,e.cancelled_at,b.status batch_status
        FROM cancellation_scope c JOIN cancellation_event e ON e.event_id=c.event_id
        JOIN cancellation_batch b ON b.event_id=e.event_id WHERE c.session_id=#{id}
        """) Map<String,Object> sessionNotice(long id);
    @Select("""
        SELECT e.event_id,e.cancel_type,e.target_id,e.reason,e.cancelled_at,b.status batch_status
        FROM cancellation_event e JOIN cancellation_batch b ON b.event_id=e.event_id
        WHERE e.cancel_type='ACTIVITY' AND e.target_id=#{id}
        """) Map<String,Object> activityNotice(long id);
}
