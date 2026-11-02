package com.limitactivitysystem.ticketorder.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Mapper
public interface UserSessionPurchaseMapper {
    @Select("SELECT valid_count FROM user_session_purchase WHERE user_id = #{userId} AND session_id = #{sessionId} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Transactional(propagation = Propagation.MANDATORY)
    Integer findForUpdate(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    @Update("""
            UPDATE user_session_purchase SET valid_count = valid_count - #{quantity}
            WHERE user_id = #{userId} AND session_id = #{sessionId}
              AND #{quantity} > 0 AND valid_count >= #{quantity}
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int tryDecrease(@Param("userId") Long userId, @Param("sessionId") Long sessionId, @Param("quantity") int quantity);

    @Insert("""
            INSERT INTO user_session_purchase (user_id, session_id, valid_count)
            VALUES (#{userId}, #{sessionId}, 0)
            ON DUPLICATE KEY UPDATE valid_count = valid_count
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int ensureExists(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    @Update("""
            UPDATE user_session_purchase SET valid_count = valid_count + #{quantity}
            WHERE user_id = #{userId} AND session_id = #{sessionId}
              AND #{quantity} > 0 AND #{purchaseLimit} > 0
              AND valid_count <= #{purchaseLimit} - #{quantity}
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int tryIncrease(@Param("userId") Long userId, @Param("sessionId") Long sessionId,
                    @Param("quantity") Integer quantity, @Param("purchaseLimit") Integer purchaseLimit);

    @Select("""
            SELECT COALESCE((
                SELECT valid_count FROM user_session_purchase
                WHERE user_id = #{userId} AND session_id = #{sessionId}
            ), 0)
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    int findValidCount(@Param("userId") Long userId, @Param("sessionId") Long sessionId);
}
