package com.limitactivitysystem.reservation.mapper;

import com.limitactivitysystem.reservation.model.FreeSessionQuota;
import org.apache.ibatis.annotations.*;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Mapper
public interface FreeSessionQuotaMapper {
    @Insert("""
            INSERT INTO free_session_quota(session_id, capacity, remaining)
            SELECT s.activity_session_id, s.capacity, s.capacity
            FROM activity_session s JOIN activity a ON a.activity_id = s.activity_id
            WHERE s.activity_session_id = #{sessionId} AND a.type = 'FREE' AND a.status = 'PUBLISHED'
            ON DUPLICATE KEY UPDATE session_id = free_session_quota.session_id
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int initializeForPublishedFreeSession(@Param("sessionId") Long sessionId);

    @Select("SELECT session_id, capacity, remaining FROM free_session_quota WHERE session_id = #{sessionId} FOR UPDATE")
    @Results({@Result(column = "session_id", property = "sessionId", id = true),
            @Result(column = "capacity", property = "capacity"), @Result(column = "remaining", property = "remaining")})
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Transactional(propagation = Propagation.MANDATORY)
    FreeSessionQuota findBySessionIdForUpdate(@Param("sessionId") Long sessionId);

    @Update("UPDATE free_session_quota SET remaining = remaining - 1 WHERE session_id = #{sessionId} AND remaining > 0")
    @Transactional(propagation = Propagation.MANDATORY)
    int takeOne(@Param("sessionId") Long sessionId);

    @Update("UPDATE free_session_quota SET remaining = remaining + 1 WHERE session_id = #{sessionId} AND remaining < capacity")
    @Transactional(propagation = Propagation.MANDATORY)
    int restoreOne(@Param("sessionId") Long sessionId);
}
