package com.limitactivitysystem.ticketorder.mapper;

import com.limitactivitysystem.ticketorder.model.Ticket;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Mapper
public interface TicketMapper {
    @Insert("""
            INSERT INTO ticket (order_item_id, seat_id, paid_price, status)
            VALUES (#{orderItemId}, #{seatId}, #{paidPrice}, 'VALID')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "ticketId", keyColumn = "ticket_id")
    @Transactional(propagation = Propagation.MANDATORY)
    int insert(Ticket ticket);

    @Select("""
            SELECT t.ticket_id, t.order_item_id, t.seat_id, t.paid_price, t.status, t.issued_at
            FROM ticket t JOIN ticket_order_item i ON i.order_item_id = t.order_item_id
            WHERE i.order_id = #{orderId} ORDER BY t.ticket_id
            """)
    @Results(id = "ticketResultMap", value = {
            @Result(column = "ticket_id", property = "ticketId", id = true),
            @Result(column = "order_item_id", property = "orderItemId"),
            @Result(column = "seat_id", property = "seatId"),
            @Result(column = "paid_price", property = "paidPrice"),
            @Result(column = "status", property = "status"),
            @Result(column = "issued_at", property = "issuedAt")
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Ticket> findByOrderId(@Param("orderId") Long orderId);
}
