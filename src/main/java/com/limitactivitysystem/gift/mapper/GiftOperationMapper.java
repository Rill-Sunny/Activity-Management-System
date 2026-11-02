package com.limitactivitysystem.gift.mapper;

import com.limitactivitysystem.gift.model.GiftInventoryOperation;
import org.apache.ibatis.annotations.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Mapper
public interface GiftOperationMapper {
    @Select("""
            SELECT operation_id, request_id, publisher_id, session_id, zone_id, operation_type, quantity, status, created_at
            FROM gift_inventory_operation WHERE publisher_id = #{publisherId} AND request_id = #{requestId}
            """)
    @Results(id = "giftOperation", value = {
            @Result(column = "operation_id", property = "operationId", id = true),
            @Result(column = "request_id", property = "requestId"),
            @Result(column = "publisher_id", property = "publisherId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "zone_id", property = "zoneId"),
            @Result(column = "operation_type", property = "operationType"),
            @Result(column = "created_at", property = "createdAt")
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    GiftInventoryOperation findByRequest(@Param("publisherId") Long publisherId, @Param("requestId") String requestId);

    @Select("SELECT operation_id, request_id, publisher_id, session_id, zone_id, operation_type, quantity, status, created_at FROM gift_inventory_operation WHERE operation_id = #{operationId}")
    @ResultMap("giftOperation")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    GiftInventoryOperation findById(@Param("operationId") Long operationId);

    @Select("""
            SELECT operation_id, request_id, publisher_id, session_id, zone_id, operation_type, quantity, status, created_at
            FROM gift_inventory_operation WHERE zone_id = #{zoneId} AND operation_id > #{afterOperationId}
            ORDER BY operation_id LIMIT #{limit}
            """)
    @ResultMap("giftOperation")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<GiftInventoryOperation> findByZoneAfter(@Param("zoneId") Long zoneId,
            @Param("afterOperationId") long afterOperationId, @Param("limit") int limit);

    @Select("SELECT seat_id FROM gift_inventory_operation_item WHERE operation_id = #{operationId} ORDER BY seat_id")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> findSeatIds(@Param("operationId") Long operationId);

    @Insert("""
            INSERT INTO gift_inventory_operation(publisher_id, session_id, zone_id, request_id, quantity, operation_type, status)
            VALUES (#{publisherId}, #{sessionId}, #{zoneId}, #{requestId}, #{quantity}, 'ALLOCATE', 'SUCCEEDED')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "operationId", keyColumn = "operation_id")
    @Transactional(propagation = Propagation.MANDATORY)
    int insert(GiftInventoryOperation operation);

    @Insert("INSERT INTO gift_inventory_operation_item(operation_id, seat_id) VALUES (#{operationId}, #{seatId})")
    @Transactional(propagation = Propagation.MANDATORY)
    int insertItem(@Param("operationId") Long operationId, @Param("seatId") Long seatId);
}
