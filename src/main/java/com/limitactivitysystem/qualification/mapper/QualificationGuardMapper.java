package com.limitactivitysystem.qualification.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Mapper
@Transactional(propagation = Propagation.MANDATORY)
public interface QualificationGuardMapper {
    @Insert("""
            INSERT INTO user_activity_qualification_guard(user_id, activity_id, qualification_version)
            VALUES(#{userId}, #{activityId}, 0)
            ON DUPLICATE KEY UPDATE qualification_version = qualification_version
            """)
    int ensureExists(@Param("userId") Long userId, @Param("activityId") Long activityId);

    @Select("""
            SELECT qualification_version
            FROM user_activity_qualification_guard
            WHERE user_id = #{userId} AND activity_id = #{activityId}
            FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockVersion(@Param("userId") Long userId, @Param("activityId") Long activityId);

    @Update("""
            UPDATE user_activity_qualification_guard
            SET qualification_version = qualification_version + 1
            WHERE user_id = #{userId} AND activity_id = #{activityId}
              AND qualification_version < 9223372036854775807
            """)
    int advanceVersion(@Param("userId") Long userId, @Param("activityId") Long activityId);
}
