package com.limitactivitysystem.messaging;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface NotificationMapper {
    @Insert("""
            INSERT INTO notification (user_id, event_id, content, status)
            VALUES (#{userId}, #{eventId}, #{content}, 'UNREAD')
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int insert(@Param("userId") Long userId, @Param("eventId") String eventId,
               @Param("content") String content);

    @Select("""
            SELECT notification_id AS notificationId, event_id AS eventId, content, status, created_at AS createdAt
            FROM notification WHERE user_id = #{userId}
            ORDER BY created_at DESC, notification_id DESC LIMIT #{limit}
            """)
    List<NotificationRecord> findForUser(@Param("userId") Long userId, @Param("limit") int limit);

    record NotificationRecord(Long notificationId, String eventId, String content, String status,
                              LocalDateTime createdAt) { }
}
