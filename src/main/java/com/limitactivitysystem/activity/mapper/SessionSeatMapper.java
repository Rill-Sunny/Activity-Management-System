package com.limitactivitysystem.activity.mapper;

import com.limitactivitysystem.activity.model.SessionSeat;
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

import java.util.List;

@Mapper
public interface SessionSeatMapper {
    @Select("""
            SELECT seat_id, zone_id, row_no, seat_no, allocation_priority, allocation_status
            FROM session_seat WHERE seat_id = #{seatId} FOR UPDATE
            """)
    @ResultMap("sessionSeatResultMap")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Transactional(propagation = Propagation.MANDATORY)
    SessionSeat findByIdForUpdate(@Param("seatId") Long seatId);

    @Update("""
            UPDATE session_seat SET allocation_status = 'AVAILABLE'
            WHERE seat_id = #{seatId} AND zone_id = #{zoneId} AND allocation_status = 'SOLD'
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int releaseSold(@Param("seatId") Long seatId, @Param("zoneId") Long zoneId);


    @Insert("""
            INSERT INTO session_seat (zone_id, row_no, seat_no, allocation_priority, allocation_status)
            VALUES (#{zoneId}, #{rowNo}, #{seatNo}, #{allocationPriority}, #{allocationStatus})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "seatId", keyColumn = "seat_id")
    int insert(SessionSeat seat);

    /** Inserts a non-null, non-empty list and fills the generated seatId of each seat. */
    @Insert("""
            <script>
            INSERT INTO session_seat (zone_id, row_no, seat_no, allocation_priority, allocation_status)
            VALUES
            <foreach collection="seats" item="seat" separator=",">
                (#{seat.zoneId}, #{seat.rowNo}, #{seat.seatNo}, #{seat.allocationPriority}, #{seat.allocationStatus})
            </foreach>
            </script>
            """)
    @Options(useGeneratedKeys = true, keyProperty = "seats.seatId", keyColumn = "seat_id")
    int batchInsert(@Param("seats") List<SessionSeat> seats);

    @Select("""
            SELECT seat_id, zone_id, row_no, seat_no, allocation_priority, allocation_status
            FROM session_seat
            WHERE zone_id = #{zoneId}
            ORDER BY row_no, seat_no, seat_id
            """)
    @Results(id = "sessionSeatResultMap", value = {
            @Result(column = "seat_id", property = "seatId", id = true),
            @Result(column = "zone_id", property = "zoneId"),
            @Result(column = "row_no", property = "rowNo"),
            @Result(column = "seat_no", property = "seatNo"),
            @Result(column = "allocation_priority", property = "allocationPriority"),
            @Result(column = "allocation_status", property = "allocationStatus")
    })
    List<SessionSeat> findByZoneId(@Param("zoneId") Long zoneId);

    @Select("""
            SELECT seat_id, zone_id, row_no, seat_no, allocation_priority, allocation_status
            FROM session_seat
            WHERE seat_id = #{id}
            """)
    @ResultMap("sessionSeatResultMap")
    SessionSeat findById(@Param("id") Long id);

    @Select("""
            SELECT seat_id, zone_id, row_no, seat_no, allocation_priority, allocation_status
            FROM session_seat WHERE zone_id = #{zoneId} AND allocation_status = 'AVAILABLE'
            ORDER BY allocation_priority, row_no, seat_no, seat_id
            """)
    @ResultMap("sessionSeatResultMap")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<SessionSeat> findAvailableByZoneId(@Param("zoneId") Long zoneId);

    @Update("""
            UPDATE session_seat SET allocation_status = 'SOLD'
            WHERE seat_id = #{seatId} AND zone_id = #{zoneId} AND allocation_status = 'AVAILABLE'
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int occupySeat(@Param("seatId") Long seatId, @Param("zoneId") Long zoneId);

    @Select("SELECT COUNT(*) FROM session_seat WHERE zone_id = #{zoneId}")
    long countByZoneId(@Param("zoneId") Long zoneId);
}
