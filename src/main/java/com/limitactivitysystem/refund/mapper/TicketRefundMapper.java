package com.limitactivitysystem.refund.mapper;

import com.limitactivitysystem.refund.dto.TicketRefundItemResponse;
import com.limitactivitysystem.refund.model.TicketRefundRequest;
import org.apache.ibatis.annotations.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TicketRefundMapper {
    @Select("""
        SELECT COUNT(*) FROM cancellation_scope c JOIN ticket_order o ON o.session_id=c.session_id
        JOIN activity_session s ON s.activity_session_id=c.session_id
        WHERE c.event_id=#{eventId} AND o.order_id=#{orderId} AND s.status='CANCELLED'
        """)
    int cancellationScope(long eventId,long orderId);
    @Insert("""
        INSERT INTO ticket_refund_request(buyer_id,order_id,request_id,reason,requested_at,status,open_time_snapshot,start_time_snapshot,
            policy_version,refund_policy,source_event_id,accepted_at)
        VALUES(#{buyerId},#{orderId},#{requestId},#{reason},#{requestedAt},'PENDING',#{openTimeSnapshot},#{startTimeSnapshot},
            'CANCELLATION_V1','CANCELLATION_REFUND',#{sourceEventId},#{requestedAt})
        """)
    @Options(useGeneratedKeys=true,keyProperty="refundId",keyColumn="refund_id")
    @Transactional(propagation=Propagation.MANDATORY)
    int insertCancellationRequest(TicketRefundRequest request);
    @Select("""
            SELECT *
            FROM ticket_refund_request WHERE buyer_id = #{buyerId} AND request_id = #{requestId}
            """)
    @Results(id = "refundRequest", value = {
            @Result(column = "refund_id", property = "refundId", id = true),
            @Result(column = "buyer_id", property = "buyerId"),
            @Result(column = "order_id", property = "orderId"),
            @Result(column = "request_id", property = "requestId"),
            @Result(column = "requested_at", property = "requestedAt"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "policy_version", property = "policyVersion"),
            @Result(column = "open_time_snapshot", property = "openTimeSnapshot"),
            @Result(column = "start_time_snapshot", property = "startTimeSnapshot"),
            @Result(column = "processed_at", property = "processedAt"),
            @Result(column = "refund_policy", property = "refundPolicy"),
            @Result(column = "source_event_id", property = "sourceEventId"),
            @Result(column = "accepted_at", property = "acceptedAt")
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    TicketRefundRequest findByRequest(@Param("buyerId") Long buyerId, @Param("requestId") String requestId);

    /** Non-locking discovery only: the order must be locked before locking this request. */
    @Select("SELECT * FROM ticket_refund_request WHERE refund_id = #{refundId}")
    @ResultMap("refundRequest")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    TicketRefundRequest findById(@Param("refundId") Long refundId);

    @Select("SELECT * FROM ticket_refund_request WHERE refund_id = #{refundId} FOR UPDATE")
    @ResultMap("refundRequest")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Transactional(propagation = Propagation.MANDATORY)
    TicketRefundRequest findByIdForUpdate(@Param("refundId") Long refundId);

    @Select("""
            SELECT refund_item_id AS refundItemId, refund_id AS refundId, ticket_id AS ticketId,
                   original_price AS originalPrice, fee_rate AS feeRate, refund_amount AS refundAmount,
                   status, failure_reason AS failureReason, processed_at AS processedAt, result
            FROM ticket_refund_item WHERE refund_id = #{refundId} ORDER BY ticket_id
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<TicketRefundItemResponse> findItems(@Param("refundId") Long refundId);

    @Select("""
            SELECT refund_item_id AS refundItemId, refund_id AS refundId, ticket_id AS ticketId,
                   original_price AS originalPrice, fee_rate AS feeRate, refund_amount AS refundAmount,
                   status, failure_reason AS failureReason, processed_at AS processedAt, result
            FROM ticket_refund_item FORCE INDEX (uq_refund_item_ticket)
            WHERE refund_id = #{refundId} ORDER BY ticket_id FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Transactional(propagation = Propagation.MANDATORY)
    List<TicketRefundItemResponse> findItemsForUpdate(@Param("refundId") Long refundId);

    @Update("UPDATE ticket_refund_request SET status = 'PROCESSING' WHERE refund_id = #{refundId} AND status = 'PENDING'")
    @Transactional(propagation = Propagation.MANDATORY)
    int markProcessing(@Param("refundId") Long refundId);

    @Update("UPDATE ticket_refund_item SET status = 'PROCESSING' WHERE refund_item_id = #{itemId} AND status = 'PENDING'")
    @Transactional(propagation = Propagation.MANDATORY)
    int markItemProcessing(@Param("itemId") Long itemId);

    @Update("""
            UPDATE ticket_refund_item SET status = 'SUCCEEDED', processed_at = #{processedAt}, result = 'SIMULATED_REFUND_SUCCEEDED'
            WHERE refund_item_id = #{itemId} AND status = 'PROCESSING'
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int markItemSucceeded(@Param("itemId") Long itemId, @Param("processedAt") LocalDateTime processedAt);

    @Update("""
            UPDATE ticket_refund_request SET status = 'SUCCEEDED', processed_at = #{processedAt}, result = 'SIMULATED_REFUND_SUCCEEDED'
            WHERE refund_id = #{refundId} AND status = 'PROCESSING'
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int markSucceeded(@Param("refundId") Long refundId, @Param("processedAt") LocalDateTime processedAt);

    @Update("""
            UPDATE ticket_refund_item SET status = 'FAILED', processed_at = #{processedAt},
                   result = 'TECHNICAL_FAILURE', failure_reason = #{reason}
            WHERE refund_item_id = #{itemId} AND status = 'PENDING'
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int markItemFailed(@Param("itemId") Long itemId, @Param("processedAt") LocalDateTime processedAt, @Param("reason") String reason);

    @Update("""
            UPDATE ticket_refund_request SET status = 'FAILED', processed_at = #{processedAt}, result = 'TECHNICAL_FAILURE'
            WHERE refund_id = #{refundId} AND status = 'PENDING'
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int markFailed(@Param("refundId") Long refundId, @Param("processedAt") LocalDateTime processedAt);

    @Update("""
            UPDATE ticket_refund_item SET status = 'PENDING', processed_at = NULL, result = NULL, failure_reason = NULL
            WHERE refund_item_id = #{itemId} AND status = 'FAILED' AND result = 'TECHNICAL_FAILURE'
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int reopenItem(@Param("itemId") Long itemId);

    @Update("""
            UPDATE ticket_refund_request SET status = 'PENDING', processed_at = NULL, result = NULL
            WHERE refund_id = #{refundId} AND status = 'FAILED' AND result = 'TECHNICAL_FAILURE'
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int reopen(@Param("refundId") Long refundId);

    @Insert("""
            INSERT INTO ticket_refund_request
                (buyer_id, order_id, request_id, reason, requested_at, status, open_time_snapshot, start_time_snapshot)
            VALUES (#{buyerId}, #{orderId}, #{requestId}, #{reason}, #{requestedAt}, 'PENDING', #{openTimeSnapshot}, #{startTimeSnapshot})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "refundId", keyColumn = "refund_id")
    @Transactional(propagation = Propagation.MANDATORY)
    int insertRequest(TicketRefundRequest request);

    @Insert("""
            INSERT INTO ticket_refund_request
                (buyer_id,order_id,request_id,reason,requested_at,status,open_time_snapshot,start_time_snapshot,
                 policy_version,refund_policy,source_event_id,accepted_at)
            VALUES (#{buyerId},#{orderId},#{requestId},#{reason},#{requestedAt},'PENDING',#{openTimeSnapshot},#{startTimeSnapshot},
                 'SESSION_RESCHEDULE_V1','SESSION_RESCHEDULE_REFUND',#{sourceEventId},#{requestedAt})
            """)
    @Options(useGeneratedKeys=true,keyProperty="refundId",keyColumn="refund_id")
    @Transactional(propagation=Propagation.MANDATORY)
    int insertRescheduleRequest(TicketRefundRequest request);

    @Insert("""
            INSERT INTO ticket_refund_item (refund_id, ticket_id, original_price, fee_rate, refund_amount, status)
            VALUES (#{refundId}, #{ticketId}, #{originalPrice}, #{feeRate}, #{refundAmount}, 'PENDING')
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int insertItem(@Param("refundId") Long refundId, @Param("ticketId") Long ticketId,
                   @Param("originalPrice") BigDecimal originalPrice, @Param("feeRate") BigDecimal feeRate,
                   @Param("refundAmount") BigDecimal refundAmount);
}
