package com.limitactivitysystem.qualification.mapper;

import com.limitactivitysystem.qualification.model.QualificationTaskCandidate;
import com.limitactivitysystem.qualification.model.TicketQualificationAfterSaleTask;
import org.apache.ibatis.annotations.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Mapper
@Transactional(propagation = Propagation.MANDATORY)
public interface QualificationAfterSaleTaskMapper {
    @Select("""
            SELECT i.order_id AS orderId, i.order_item_id AS orderItemId,
                   i.activity_id_snapshot AS activityId,
                   i.qualification_scope_snapshot AS qualificationScope,
                   i.qualification_session_id_snapshot AS qualificationSessionId
            FROM product_order_item i
            JOIN product_order o ON o.order_id=i.order_id
            WHERE o.buyer_id=#{buyerId} AND o.status IN ('PAID','PARTIALLY_REFUNDED')
              AND i.qualification_type_snapshot='TICKET_REQUIRED'
              AND i.activity_id_snapshot=#{activityId}
              AND (i.qualification_scope_snapshot='ACTIVITY'
                   OR i.qualification_session_id_snapshot=#{sessionId})
              AND i.quantity > i.refunded_quantity + i.refund_pending_quantity
            ORDER BY i.order_id, i.order_item_id
            """)
    @ConstructorArgs({
            @Arg(column = "orderId", javaType = Long.class),
            @Arg(column = "orderItemId", javaType = Long.class),
            @Arg(column = "activityId", javaType = Long.class),
            @Arg(column = "qualificationScope", javaType = String.class),
            @Arg(column = "qualificationSessionId", javaType = Long.class)
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<QualificationTaskCandidate> findCandidates(@Param("buyerId") Long buyerId,
                                                     @Param("activityId") Long activityId,
                                                     @Param("sessionId") Long sessionId);

    @Insert("""
            INSERT INTO ticket_qualification_after_sale_task(
                source_event_id, product_order_item_id, qualification_scope, status, attempt_count, next_attempt_at
            ) VALUES (#{sourceEventId}, #{productOrderItemId}, #{qualificationScope}, #{status}, 0,
                      CASE WHEN #{status}='SKIPPED' THEN NULL ELSE CURRENT_TIMESTAMP(3) END)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "taskId", keyColumn = "task_id")
    int insert(TicketQualificationAfterSaleTask task);

    @Select("""
            SELECT task_id, source_event_id, product_order_item_id, qualification_scope,
                   status, attempt_count, next_attempt_at, failure_reason
            FROM ticket_qualification_after_sale_task
            WHERE product_order_item_id=#{orderItemId}
            FOR UPDATE
            """)
    @Results(id = "qualificationTaskResult", value = {
            @Result(column = "task_id", property = "taskId", id = true),
            @Result(column = "source_event_id", property = "sourceEventId"),
            @Result(column = "product_order_item_id", property = "productOrderItemId"),
            @Result(column = "qualification_scope", property = "qualificationScope"),
            @Result(column = "status", property = "status"),
            @Result(column = "attempt_count", property = "attemptCount"),
            @Result(column = "next_attempt_at", property = "nextAttemptAt"),
            @Result(column = "failure_reason", property = "failureReason")
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    TicketQualificationAfterSaleTask findByOrderItemForUpdate(@Param("orderItemId") Long orderItemId);

    @Select("""
            SELECT t.task_id, t.source_event_id, t.product_order_item_id, t.qualification_scope,
                   t.status, t.attempt_count, t.next_attempt_at, t.failure_reason,
                   o.order_id AS orderId, o.buyer_id AS buyerId
            FROM ticket_qualification_after_sale_task t
            JOIN product_order_item i ON i.order_item_id=t.product_order_item_id
            JOIN product_order o ON o.order_id=i.order_id
            WHERE t.task_id=#{taskId}
            """)
    @Results(id = "qualificationTaskContextResult", value = {
            @Result(column = "task_id", property = "taskId", id = true),
            @Result(column = "source_event_id", property = "sourceEventId"),
            @Result(column = "product_order_item_id", property = "productOrderItemId"),
            @Result(column = "qualification_scope", property = "qualificationScope"),
            @Result(column = "status", property = "status"),
            @Result(column = "attempt_count", property = "attemptCount"),
            @Result(column = "next_attempt_at", property = "nextAttemptAt"),
            @Result(column = "failure_reason", property = "failureReason"),
            @Result(column = "orderId", property = "orderId"),
            @Result(column = "buyerId", property = "buyerId")
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    TicketQualificationAfterSaleTask findContext(@Param("taskId") Long taskId);

    @Select("""
            SELECT task_id, source_event_id, product_order_item_id, qualification_scope,
                   status, attempt_count, next_attempt_at, failure_reason
            FROM ticket_qualification_after_sale_task WHERE task_id=#{taskId} FOR UPDATE
            """)
    @ResultMap("qualificationTaskResult")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    TicketQualificationAfterSaleTask findByIdForUpdate(@Param("taskId") Long taskId);

    @Select("""
            SELECT task_id, source_event_id, product_order_item_id, qualification_scope,
                   status, attempt_count, next_attempt_at, failure_reason
            FROM ticket_qualification_after_sale_task WHERE task_id=#{taskId}
            """)
    @ResultMap("qualificationTaskResult")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    TicketQualificationAfterSaleTask findById(@Param("taskId") Long taskId);

    @Select("""
            SELECT task_id FROM ticket_qualification_after_sale_task
            WHERE source_event_id=#{eventId} ORDER BY task_id
            """)
    List<Long> findIdsBySourceEventId(@Param("eventId") String eventId);

    @Select("""
            SELECT t.task_id
            FROM ticket_qualification_after_sale_task t
            LEFT JOIN product_order_item i ON i.order_item_id=t.product_order_item_id
            WHERE (t.status='PENDING' AND t.next_attempt_at <= CURRENT_TIMESTAMP(3))
               OR (t.status='FAILED' AND t.attempt_count < 5
                   AND t.next_attempt_at <= CURRENT_TIMESTAMP(3))
               OR (t.status='WAITING_RETURN' AND i.fulfillment_status='RECEIVED')
            ORDER BY COALESCE(t.next_attempt_at, t.created_at), t.task_id
            LIMIT #{limit}
            """)
    List<Long> findDueTaskIds(@Param("limit") int limit);

    @Update("""
            UPDATE ticket_qualification_after_sale_task
            SET status='PROCESSING', attempt_count=attempt_count+1,
                next_attempt_at=CURRENT_TIMESTAMP(3), failure_reason=NULL
            WHERE task_id=#{taskId} AND attempt_count < 5
              AND (status='PENDING' OR status='WAITING_RETURN'
                   OR (status='FAILED' AND next_attempt_at <= CURRENT_TIMESTAMP(3)))
            """)
    int markProcessing(@Param("taskId") Long taskId);

    @Update("""
            UPDATE ticket_qualification_after_sale_task
            SET status='WAITING_RETURN', next_attempt_at=NULL, failure_reason=NULL
            WHERE task_id=#{taskId} AND status IN ('PENDING','PROCESSING','WAITING_RETURN')
            """)
    int markWaitingReturn(@Param("taskId") Long taskId);

    @Update("""
            UPDATE ticket_qualification_after_sale_task
            SET status='PENDING', next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 5 SECOND), failure_reason=NULL
            WHERE task_id=#{taskId} AND status IN ('PENDING','PROCESSING')
            """)
    int deferPending(@Param("taskId") Long taskId);

    @Update("""
            UPDATE ticket_qualification_after_sale_task
            SET status='PENDING', source_event_id=#{eventId}, attempt_count=0,
                next_attempt_at=CURRENT_TIMESTAMP(3), failure_reason=NULL
            WHERE task_id=#{taskId} AND status='SKIPPED'
            """)
    int reopenSkipped(@Param("taskId") Long taskId, @Param("eventId") String eventId);

    @Update("""
            UPDATE ticket_qualification_after_sale_task
            SET status='SKIPPED', next_attempt_at=NULL, failure_reason=NULL
            WHERE task_id=#{taskId} AND status IN ('PENDING','PROCESSING','WAITING_RETURN','FAILED')
            """)
    int markSkipped(@Param("taskId") Long taskId);

    @Update("""
            UPDATE ticket_qualification_after_sale_task
            SET status='SUCCEEDED', next_attempt_at=NULL, failure_reason=NULL
            WHERE task_id=#{taskId} AND status IN ('PENDING','PROCESSING','WAITING_RETURN','FAILED')
            """)
    int markSucceeded(@Param("taskId") Long taskId);

    @Update("""
            UPDATE ticket_qualification_after_sale_task
            SET next_attempt_at=CASE WHEN attempt_count+1 < 5
                    THEN DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 5 SECOND) ELSE NULL END,
                status='FAILED', attempt_count=LEAST(attempt_count+1, 5),
                failure_reason=#{reason}
            WHERE task_id=#{taskId} AND status IN ('PENDING','PROCESSING','WAITING_RETURN','FAILED')
            """)
    int markFailed(@Param("taskId") Long taskId, @Param("reason") String reason);

    @Update("""
            UPDATE ticket_qualification_after_sale_task
            SET status='PENDING', attempt_count=0, next_attempt_at=CURRENT_TIMESTAMP(3), failure_reason=NULL
            WHERE task_id=#{taskId} AND status='FAILED' AND attempt_count >= 5 AND next_attempt_at IS NULL
            """)
    int resetFailedForReplay(@Param("taskId") Long taskId);
}
