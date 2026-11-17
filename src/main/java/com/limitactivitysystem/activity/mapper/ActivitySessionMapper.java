package com.limitactivitysystem.activity.mapper;

import com.limitactivitysystem.activity.model.ActivitySession;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ActivitySessionMapper {

    @Insert("""
            INSERT INTO activity_session
                (activity_id, start_time, end_time, capacity, status, max_tickets_per_user, seat_mode)
            VALUES
                (#{activityId}, #{startTime}, #{endTime}, #{capacity}, #{status}, #{maxTicketsPerUser}, #{seatMode})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "activitySessionId", keyColumn = "activity_session_id")
    int insert(ActivitySession session);

    @Select("""
            SELECT activity_session_id, activity_id, start_time, end_time, capacity, status,
                   max_tickets_per_user, seat_mode
            FROM activity_session
            WHERE activity_session_id = #{id}
            """)
    @Results(id = "activitySessionResultMap", value = {
            @Result(column = "activity_session_id", property = "activitySessionId", id = true),
            @Result(column = "activity_id", property = "activityId"),
            @Result(column = "start_time", property = "startTime"),
            @Result(column = "end_time", property = "endTime"),
            @Result(column = "capacity", property = "capacity"),
            @Result(column = "status", property = "status"),
            @Result(column = "max_tickets_per_user", property = "maxTicketsPerUser"),
            @Result(column = "seat_mode", property = "seatMode")
    })
    ActivitySession findById(@Param("id") Long id);

    @Select("""
            SELECT activity_session_id, activity_id, start_time, end_time, capacity, status,
                   max_tickets_per_user, seat_mode
            FROM activity_session WHERE activity_session_id = #{id} FOR SHARE
            """)
    @ResultMap("activitySessionResultMap")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ActivitySession findByIdForShare(@Param("id") Long id);

    @Select("SELECT * FROM activity_session WHERE activity_session_id = #{id} FOR UPDATE")
    @ResultMap("activitySessionResultMap")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ActivitySession findByIdForUpdate(@Param("id") Long id);

    @org.apache.ibatis.annotations.Update("""
            UPDATE activity_session SET start_time=#{start}, end_time=#{end}
            WHERE activity_session_id=#{id} AND status='SCHEDULED'
            """)
    int reschedule(@Param("id") Long id, @Param("start") java.time.LocalDateTime start,
                   @Param("end") java.time.LocalDateTime end);

    @Select("""
            SELECT activity_session_id, activity_id, start_time, end_time, capacity, status,
                   max_tickets_per_user, seat_mode
            FROM activity_session
            WHERE activity_id = #{activityId}
            ORDER BY start_time, activity_session_id
            """)
    @ResultMap("activitySessionResultMap")
    List<ActivitySession> findByActivityId(@Param("activityId") Long activityId);

    @Select("SELECT COUNT(*) FROM activity_session WHERE activity_id = #{activityId}")
    long countByActivityId(@Param("activityId") Long activityId);
}
