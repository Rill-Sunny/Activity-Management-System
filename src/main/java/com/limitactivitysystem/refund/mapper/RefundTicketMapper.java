package com.limitactivitysystem.refund.mapper;

import com.limitactivitysystem.refund.model.RefundTicketOwnership;
import com.limitactivitysystem.refund.model.RefundTicketResource;
import com.limitactivitysystem.ticketorder.model.Ticket;
import org.apache.ibatis.annotations.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Mapper
public interface RefundTicketMapper {
    @Select("""
            SELECT t.ticket_id AS ticketId, i.order_id AS orderId, o.buyer_id AS buyerId
            FROM ticket t JOIN ticket_order_item i ON i.order_item_id = t.order_item_id
            JOIN ticket_order o ON o.order_id = i.order_id
            WHERE t.ticket_id = #{ticketId}
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    RefundTicketOwnership findOwnership(@Param("ticketId") Long ticketId);

    @Select("""
            SELECT ticket_id, order_item_id, seat_id, paid_price, status, issued_at
            FROM ticket WHERE ticket_id = #{ticketId} FOR UPDATE
            """)
    @ResultMap("com.limitactivitysystem.ticketorder.mapper.TicketMapper.ticketResultMap")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Transactional(propagation = Propagation.MANDATORY)
    Ticket lockTicket(@Param("ticketId") Long ticketId);

    @Select("""
            SELECT t.ticket_id AS ticketId, t.order_item_id AS orderItemId, i.order_id AS orderId,
                   o.buyer_id AS buyerId, o.session_id AS sessionId, i.zone_id AS zoneId, t.seat_id AS seatId
            FROM ticket t JOIN ticket_order_item i ON i.order_item_id = t.order_item_id
            JOIN ticket_order o ON o.order_id = i.order_id WHERE t.ticket_id = #{ticketId}
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    RefundTicketResource findResource(@Param("ticketId") Long ticketId);

    @Update("""
            UPDATE ticket SET status = 'REFUNDED', refunded_at = #{processedAt}
            WHERE ticket_id = #{ticketId} AND status = 'VALID'
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int markRefunded(@Param("ticketId") Long ticketId, @Param("processedAt") java.time.LocalDateTime processedAt);

    @Select("""
            SELECT COUNT(*) FROM ticket t JOIN ticket_order_item i ON i.order_item_id = t.order_item_id
            WHERE i.order_id = #{orderId} AND t.status = 'VALID'
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    long countValidByOrder(@Param("orderId") Long orderId);
}
