package com.limitactivitysystem.activity.mapper;

import com.limitactivitysystem.activity.model.SessionZone;
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
public interface SessionZoneMapper {
    @Update("""
            UPDATE session_zone SET remains_amount = remains_amount + #{quantity}
            WHERE zone_id = #{zoneId} AND session_id = #{sessionId} AND zone_type = 'PAID'
              AND #{quantity} > 0 AND remains_amount <= total_amount - #{quantity}
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int restoreStock(@Param("zoneId") Long zoneId, @Param("sessionId") Long sessionId, @Param("quantity") int quantity);


    @Insert("""
            INSERT INTO session_zone (session_id, name, zone_type, price, total_amount, remains_amount)
            VALUES (#{sessionId}, #{name}, #{zoneType}, #{price}, #{totalAmount}, #{remainsAmount})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "zoneId", keyColumn = "zone_id")
    int insert(SessionZone zone);

    @Select("""
            SELECT zone_id, session_id, name, zone_type, price, total_amount, remains_amount
            FROM session_zone
            WHERE zone_id = #{id}
            """)
    @Results(id = "sessionZoneResultMap", value = {
            @Result(column = "zone_id", property = "zoneId", id = true),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "name", property = "name"),
            @Result(column = "zone_type", property = "zoneType"),
            @Result(column = "price", property = "price"),
            @Result(column = "total_amount", property = "totalAmount"),
            @Result(column = "remains_amount", property = "remainsAmount")
    })
    SessionZone findById(@Param("id") Long id);

    @Select("""
            SELECT zone_id, session_id, name, zone_type, price, total_amount, remains_amount
            FROM session_zone WHERE zone_id = #{zoneId} FOR UPDATE
            """)
    @ResultMap("sessionZoneResultMap")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Transactional(propagation = Propagation.MANDATORY)
    SessionZone findByIdForUpdate(@Param("zoneId") Long zoneId);

    @Select("""
            SELECT zone_id, session_id, name, zone_type, price, total_amount, remains_amount
            FROM session_zone WHERE zone_id = #{zoneId} FOR SHARE
            """)
    @ResultMap("sessionZoneResultMap")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Transactional(propagation = Propagation.MANDATORY)
    SessionZone findByIdForShare(@Param("zoneId") Long zoneId);

    @Update("""
            UPDATE session_zone SET remains_amount = remains_amount - #{quantity}
            WHERE zone_id = #{zoneId} AND zone_type = 'PAID'
              AND #{quantity} > 0 AND remains_amount >= #{quantity}
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int decreaseStock(@Param("zoneId") Long zoneId, @Param("quantity") Integer quantity);

    @Select("""
            SELECT zone_id, session_id, name, zone_type, price, total_amount, remains_amount
            FROM session_zone
            WHERE session_id = #{sessionId}
            ORDER BY zone_id
            """)
    @ResultMap("sessionZoneResultMap")
    List<SessionZone> findBySessionId(@Param("sessionId") Long sessionId);

    @Select("SELECT COALESCE(SUM(total_amount), 0) FROM session_zone WHERE session_id = #{sessionId}")
    long sumTotalAmountBySessionId(@Param("sessionId") Long sessionId);
}
