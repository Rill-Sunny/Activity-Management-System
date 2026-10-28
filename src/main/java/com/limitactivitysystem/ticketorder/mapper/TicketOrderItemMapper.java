package com.limitactivitysystem.ticketorder.mapper;

import com.limitactivitysystem.ticketorder.model.TicketOrderItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TicketOrderItemMapper {

    @Insert("""
            INSERT INTO ticket_order_item
                (order_id, zone_id, allocation_mode, requested_seat_id, quantity, unit_price, accept_non_adjacent)
            VALUES
                (#{orderId}, #{zoneId}, #{allocationMode}, #{requestedSeatId,jdbcType=BIGINT},
                 #{quantity}, #{unitPrice}, #{acceptNonAdjacent})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "orderItemId", keyColumn = "order_item_id")
    int insert(TicketOrderItem item);

    /** The caller supplies a non-null, non-empty list. Each generated orderItemId is filled back. */
    @Insert("""
            <script>
            INSERT INTO ticket_order_item
                (order_id, zone_id, allocation_mode, requested_seat_id, quantity, unit_price, accept_non_adjacent)
            VALUES
            <foreach collection="items" item="item" separator=",">
                (#{item.orderId}, #{item.zoneId}, #{item.allocationMode}, #{item.requestedSeatId,jdbcType=BIGINT},
                 #{item.quantity}, #{item.unitPrice}, #{item.acceptNonAdjacent})
            </foreach>
            </script>
            """)
    @Options(useGeneratedKeys = true, keyProperty = "items.orderItemId", keyColumn = "order_item_id")
    int batchInsert(@Param("items") List<TicketOrderItem> items);

    @Select("""
            SELECT order_item_id, order_id, zone_id, allocation_mode, requested_seat_id,
                   quantity, unit_price, accept_non_adjacent
            FROM ticket_order_item
            WHERE order_id = #{orderId}
            ORDER BY order_item_id
            """)
    @Results(id = "ticketOrderItemResultMap", value = {
            @Result(column = "order_item_id", property = "orderItemId", id = true),
            @Result(column = "order_id", property = "orderId"),
            @Result(column = "zone_id", property = "zoneId"),
            @Result(column = "allocation_mode", property = "allocationMode"),
            @Result(column = "requested_seat_id", property = "requestedSeatId"),
            @Result(column = "quantity", property = "quantity"),
            @Result(column = "unit_price", property = "unitPrice"),
            @Result(column = "accept_non_adjacent", property = "acceptNonAdjacent")
    })
    List<TicketOrderItem> findByOrderId(@Param("orderId") Long orderId);
}
