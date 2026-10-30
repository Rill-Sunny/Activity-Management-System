package com.limitactivitysystem.refund.mapper;

import com.limitactivitysystem.refund.dto.TicketRefundItemResponse;
import com.limitactivitysystem.refund.model.TicketRefundRequest;
import org.apache.ibatis.annotations.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface TicketRefundMapper {
    @Select("""
            SELECT refund_id, buyer_id, order_id, request_id, reason, requested_at, status, created_at,
                   policy_version, open_time_snapshot, start_time_snapshot
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
            @Result(column = "start_time_snapshot", property = "startTimeSnapshot")
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    TicketRefundRequest findByRequest(@Param("buyerId") Long buyerId, @Param("requestId") String requestId);

    @Select("""
            SELECT refund_item_id AS refundItemId, refund_id AS refundId, ticket_id AS ticketId,
                   original_price AS originalPrice, fee_rate AS feeRate, refund_amount AS refundAmount,
                   status, failure_reason AS failureReason
            FROM ticket_refund_item WHERE refund_id = #{refundId} ORDER BY ticket_id
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<TicketRefundItemResponse> findItems(@Param("refundId") Long refundId);

    @Insert("""
            INSERT INTO ticket_refund_request
                (buyer_id, order_id, request_id, reason, requested_at, status, open_time_snapshot, start_time_snapshot)
            VALUES (#{buyerId}, #{orderId}, #{requestId}, #{reason}, #{requestedAt}, 'PENDING', #{openTimeSnapshot}, #{startTimeSnapshot})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "refundId", keyColumn = "refund_id")
    @Transactional(propagation = Propagation.MANDATORY)
    int insertRequest(TicketRefundRequest request);

    @Insert("""
            INSERT INTO ticket_refund_item (refund_id, ticket_id, original_price, fee_rate, refund_amount, status)
            VALUES (#{refundId}, #{ticketId}, #{originalPrice}, #{feeRate}, #{refundAmount}, 'PENDING')
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int insertItem(@Param("refundId") Long refundId, @Param("ticketId") Long ticketId,
                   @Param("originalPrice") BigDecimal originalPrice, @Param("feeRate") BigDecimal feeRate,
                   @Param("refundAmount") BigDecimal refundAmount);
}
