package com.limitactivitysystem.gift.mapper;

import com.limitactivitysystem.activity.model.SessionSeat;
import org.apache.ibatis.annotations.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Mapper
public interface GiftInventoryMapper {
    @Select("""
            SELECT s.seat_id, s.zone_id, s.row_no, s.seat_no, s.allocation_priority, s.allocation_status
            FROM session_seat s
            WHERE s.zone_id = #{zoneId} AND s.allocation_status = 'AVAILABLE'
              AND EXISTS (SELECT 1 FROM session_zone z WHERE z.zone_id = s.zone_id AND z.zone_type = 'GIFT')
            ORDER BY s.allocation_priority, s.row_no, s.seat_no, s.seat_id LIMIT #{quantity}
            """)
    @ResultMap("com.limitactivitysystem.activity.mapper.SessionSeatMapper.sessionSeatResultMap")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Transactional(propagation = Propagation.MANDATORY)
    List<SessionSeat> findAvailableSeats(@Param("zoneId") Long zoneId, @Param("quantity") int quantity);

    @Update("""
            UPDATE session_seat s SET s.allocation_status = 'GIFT_ALLOCATED'
            WHERE s.seat_id = #{seatId} AND s.zone_id = #{zoneId} AND s.allocation_status = 'AVAILABLE'
              AND EXISTS (SELECT 1 FROM session_zone z WHERE z.zone_id = s.zone_id AND z.zone_type = 'GIFT')
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int occupySeat(@Param("seatId") Long seatId, @Param("zoneId") Long zoneId);

    @Update("""
            UPDATE session_zone SET remains_amount = remains_amount - #{quantity}
            WHERE zone_id = #{zoneId} AND session_id = #{sessionId} AND zone_type = 'GIFT'
              AND #{quantity} > 0 AND remains_amount >= #{quantity}
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int decreaseStock(@Param("zoneId") Long zoneId, @Param("sessionId") Long sessionId, @Param("quantity") int quantity);
}
