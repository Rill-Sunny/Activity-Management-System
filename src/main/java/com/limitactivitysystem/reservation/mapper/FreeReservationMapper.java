package com.limitactivitysystem.reservation.mapper;

import com.limitactivitysystem.reservation.model.FreeReservation;
import org.apache.ibatis.annotations.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Mapper
public interface FreeReservationMapper {
    @Select("SELECT CURRENT_TIMESTAMP(3)")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    LocalDateTime currentTime();

    @Select("""
            SELECT reservation_id, user_id, session_id, status, created_at, cancelled_at
            FROM free_reservation WHERE reservation_id = #{id}
            """)
    @Results(id = "reservationResult", value = {
            @Result(column = "reservation_id", property = "reservationId", id = true),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "status", property = "status"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "cancelled_at", property = "cancelledAt")
    })
    FreeReservation findById(@Param("id") Long id);

    @Select("""
            SELECT reservation_id, user_id, session_id, status, created_at, cancelled_at
            FROM free_reservation WHERE reservation_id = #{id} FOR UPDATE
            """)
    @ResultMap("reservationResult")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Transactional(propagation = Propagation.MANDATORY)
    FreeReservation findByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT reservation_id, user_id, session_id, status, created_at, cancelled_at
            FROM free_reservation WHERE user_id = #{userId} AND active_session_id = #{sessionId}
            """)
    @ResultMap("reservationResult")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    FreeReservation findActive(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    @Insert("""
            INSERT INTO free_reservation(user_id, session_id)
            SELECT #{userId}, s.activity_session_id
            FROM activity_session s JOIN activity a ON a.activity_id = s.activity_id
            WHERE s.activity_session_id = #{sessionId}
              AND a.type = 'FREE' AND a.status = 'PUBLISHED' AND s.status = 'SCHEDULED'
              AND CURRENT_TIMESTAMP(3) >= a.open_time AND CURRENT_TIMESTAMP(3) < a.close_time
              AND CURRENT_TIMESTAMP(3) < s.start_time
            """)
    @Options(useGeneratedKeys = true, keyProperty = "reservationId", keyColumn = "reservation_id")
    @Transactional(propagation = Propagation.MANDATORY)
    int insertIfReservable(FreeReservation reservation);

    @Update("""
            UPDATE free_reservation r JOIN activity_session s ON s.activity_session_id = r.session_id
            SET r.status = 'CANCELLED', r.cancelled_at = CURRENT_TIMESTAMP(3)
            WHERE r.reservation_id = #{id} AND r.user_id = #{userId} AND r.session_id = #{sessionId}
              AND r.status = 'ACTIVE' AND CURRENT_TIMESTAMP(3) < s.start_time
              AND CURRENT_TIMESTAMP(3) >= r.created_at
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int cancelIfActiveBeforeStart(@Param("id") Long id, @Param("userId") Long userId, @Param("sessionId") Long sessionId);
}
