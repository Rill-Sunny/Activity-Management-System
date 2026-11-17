package com.limitactivitysystem.messaging;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Mapper
public interface ConsumerFailureMapper {
    @Insert("""
            INSERT INTO consumer_failure (consumer_name, event_id, attempts, status, last_error)
            VALUES (#{consumerName}, #{eventId}, #{attempts}, #{status}, #{reason})
            ON DUPLICATE KEY UPDATE attempts = GREATEST(attempts, #{attempts}),
                status = #{status}, last_error = #{reason}, updated_at = CURRENT_TIMESTAMP(3)
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int record(@Param("consumerName") String consumerName, @Param("eventId") String eventId,
               @Param("attempts") int attempts, @Param("status") String status, @Param("reason") String reason);

    @Delete("DELETE FROM consumer_failure WHERE consumer_name = #{consumerName} AND event_id = #{eventId}")
    @Transactional(propagation = Propagation.MANDATORY)
    int delete(@Param("consumerName") String consumerName, @Param("eventId") String eventId);

    @Delete("DELETE FROM consumer_failure WHERE event_id = #{eventId}")
    @Transactional(propagation = Propagation.MANDATORY)
    int deleteByEvent(@Param("eventId") String eventId);
}
