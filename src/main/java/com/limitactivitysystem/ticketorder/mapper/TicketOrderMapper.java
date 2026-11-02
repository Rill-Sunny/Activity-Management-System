package com.limitactivitysystem.ticketorder.mapper;

import com.limitactivitysystem.ticketorder.model.TicketOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TicketOrderMapper {
    @Update("""
            UPDATE ticket_order SET status = #{status}
            WHERE order_id = #{orderId} AND status IN ('PAID', 'PARTIALLY_REFUNDED')
              AND #{status} IN ('PARTIALLY_REFUNDED', 'REFUNDED')
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int updateRefundSummary(@Param("orderId") Long orderId, @Param("status") String status);

    /** Creates a pending order. Reload by ID for database-created timestamps and other generated fields. */
    @Insert("""
            INSERT INTO ticket_order (buyer_id, session_id, request_id, total_amount, status)
            VALUES (#{buyerId}, #{sessionId}, #{requestId}, #{totalAmount}, 'PENDING_PAYMENT')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "orderId", keyColumn = "order_id")
    int insert(TicketOrder order);

    /** Uses bounds read under the qualification locks. The INSERT statement checks database time again. */
    @Insert("""
            INSERT INTO ticket_order (buyer_id, session_id, request_id, total_amount, status)
            SELECT #{order.buyerId}, #{order.sessionId}, #{order.requestId}, #{order.totalAmount}, 'PENDING_PAYMENT'
            FROM DUAL
            WHERE CURRENT_TIMESTAMP(3) >= #{openTime}
              AND CURRENT_TIMESTAMP(3) < #{closeTime}
              AND CURRENT_TIMESTAMP(3) < #{startTime}
            """)
    @Options(useGeneratedKeys = true, keyProperty = "order.orderId", keyColumn = "order_id")
    int insertDuringCreationWindow(@Param("order") TicketOrder order,
                                   @Param("openTime") LocalDateTime openTime,
                                   @Param("closeTime") LocalDateTime closeTime,
                                   @Param("startTime") LocalDateTime startTime);

    @Select("""
            SELECT order_id, buyer_id, session_id, request_id, total_amount, status,
                   created_at, expires_at, paid_at, closed_reason
            FROM ticket_order
            WHERE order_id = #{id}
            """)
    @Results(id = "ticketOrderResultMap", value = {
            @Result(column = "order_id", property = "orderId", id = true),
            @Result(column = "buyer_id", property = "buyerId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "request_id", property = "requestId"),
            @Result(column = "total_amount", property = "totalAmount"),
            @Result(column = "status", property = "status"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "paid_at", property = "paidAt"),
            @Result(column = "closed_reason", property = "closedReason")
    })
    TicketOrder findById(@Param("id") Long id);

    @Select("""
            SELECT order_id, buyer_id, session_id, request_id, total_amount, status,
                   created_at, expires_at, paid_at, closed_reason
            FROM ticket_order WHERE order_id = #{orderId} FOR UPDATE
            """)
    @ResultMap("ticketOrderResultMap")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Transactional(propagation = Propagation.MANDATORY)
    TicketOrder findByIdForUpdate(@Param("orderId") Long orderId);

    @Update("""
            UPDATE ticket_order o
            JOIN activity_session s ON s.activity_session_id = o.session_id
            JOIN activity a ON a.activity_id = s.activity_id
            SET o.status = 'PAID', o.paid_at = CURRENT_TIMESTAMP(3)
            WHERE o.order_id = #{orderId} AND o.status = 'PENDING_PAYMENT'
              AND CURRENT_TIMESTAMP(3) >= o.created_at AND CURRENT_TIMESTAMP(3) < o.expires_at
              AND s.status = 'SCHEDULED' AND CURRENT_TIMESTAMP(3) < s.start_time
              AND a.status = 'PUBLISHED' AND a.type = 'PAID'
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int markPaidIfPayable(@Param("orderId") Long orderId);

    @Update("""
            UPDATE ticket_order SET status = 'CLOSED', closed_reason = 'INSUFFICIENT_INVENTORY'
            WHERE order_id = #{orderId} AND status = 'PENDING_PAYMENT'
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int closePendingForInsufficientInventory(@Param("orderId") Long orderId);

    @Update("""
            UPDATE ticket_order SET status = 'CLOSED', closed_reason = 'USER_CANCELLED'
            WHERE order_id = #{orderId} AND buyer_id = #{buyerId} AND status = 'PENDING_PAYMENT'
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int closePendingForBuyer(@Param("orderId") Long orderId, @Param("buyerId") Long buyerId);

    /** The conditional UPDATE locks the same Order row as payment and rechecks its current state. */
    @Update("""
            UPDATE ticket_order SET status = 'CLOSED', closed_reason = 'EXPIRED'
            WHERE order_id = #{orderId} AND status = 'PENDING_PAYMENT'
              AND expires_at <= CURRENT_TIMESTAMP(3)
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int closePendingIfExpired(@Param("orderId") Long orderId);

    /** Non-locking, bounded scan using idx_ticket_order_expiry (status, expires_at). */
    @Select("""
            SELECT order_id FROM ticket_order
            WHERE status = 'PENDING_PAYMENT' AND expires_at <= CURRENT_TIMESTAMP(3)
            ORDER BY expires_at, order_id
            LIMIT #{limit}
            """)
    List<Long> findExpiredPendingOrderIds(@Param("limit") int limit);

    /** The lookup supports creation idempotency; conflict recovery still needs a Service transaction boundary. */
    @Select("""
            SELECT order_id, buyer_id, session_id, request_id, total_amount, status,
                   created_at, expires_at, paid_at, closed_reason
            FROM ticket_order
            WHERE buyer_id = #{buyerId} AND request_id = #{requestId}
            """)
    @ResultMap("ticketOrderResultMap")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    TicketOrder findByBuyerIdAndRequestId(@Param("buyerId") Long buyerId, @Param("requestId") String requestId);
}
