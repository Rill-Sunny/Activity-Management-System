package com.limitactivitysystem.activity.mapper;

import com.limitactivitysystem.activity.model.Activity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ActivityMapper {

    @Insert("""
            INSERT INTO activity (publisher_id, name, description, type, status, open_time, close_time)
            VALUES (#{publisherId}, #{name}, #{description}, #{type}, #{status}, #{openTime}, #{closeTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "activityId", keyColumn = "activity_id")
    int insert(Activity activity);

    @Select("""
            SELECT activity_id, publisher_id, name, description, type, status, open_time, close_time
            FROM activity
            WHERE activity_id = #{id}
            """)
    @Results(id = "activityResultMap", value = {
            @Result(column = "activity_id", property = "activityId", id = true),
            @Result(column = "publisher_id", property = "publisherId"),
            @Result(column = "name", property = "name"),
            @Result(column = "description", property = "description"),
            @Result(column = "type", property = "type"),
            @Result(column = "status", property = "status"),
            @Result(column = "open_time", property = "openTime"),
            @Result(column = "close_time", property = "closeTime")
    })
    Activity findById(@Param("id") Long id);

    @Select("""
            SELECT activity_id, publisher_id, name, description, type, status, open_time, close_time
            FROM activity WHERE activity_id = #{id} FOR SHARE
            """)
    @ResultMap("activityResultMap")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Activity findByIdForShare(@Param("id") Long id);

    @Select("""
            SELECT activity_id, publisher_id, name, description, type, status, open_time, close_time
            FROM activity
            WHERE status = 'PUBLISHED'
            ORDER BY activity_id DESC
            """)
    @ResultMap("activityResultMap")
    List<Activity> findPublished();

    @Select("""
            SELECT activity_id, publisher_id, name, description, type, status, open_time, close_time
            FROM activity
            WHERE activity_id = #{id}
              AND status IN ('PUBLISHED','CANCELLED')
            """)
    @ResultMap("activityResultMap")
    Activity findPublishedById(@Param("id") Long id);

    /**
     * Call inside the same Service transaction as the configuration or publish operation.
     * The database row lock lasts until that transaction commits or rolls back.
     * This coordinates configuration lifecycle operations, not ticket purchases or payments.
     */
    @Select("""
            SELECT activity_id, publisher_id, name, description, type, status, open_time, close_time
            FROM activity
            WHERE activity_id = #{id}
            FOR UPDATE
            """)
    @ResultMap("activityResultMap")
    // Execute every locking read against the database, including repeated reads in one SqlSession.
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Activity findByIdForUpdate(@Param("id") Long id);

    /** Updates a DRAFT activity only; callers must require exactly one affected row. */
    @Update("""
            UPDATE activity
            SET status = #{status}
            WHERE activity_id = #{id}
              AND status = 'DRAFT'
            """)
    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
