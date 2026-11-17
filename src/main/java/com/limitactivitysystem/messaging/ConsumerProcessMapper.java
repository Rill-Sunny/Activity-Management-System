package com.limitactivitysystem.messaging;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Mapper
public interface ConsumerProcessMapper {
    @Insert("INSERT IGNORE INTO consumer_process (consumer_name, event_id) VALUES (#{consumerName}, #{eventId})")
    @Transactional(propagation = Propagation.MANDATORY)
    int begin(@Param("consumerName") String consumerName, @Param("eventId") String eventId);

    @Select("SELECT EXISTS(SELECT 1 FROM consumer_process WHERE event_id = #{eventId})")
    boolean existsForEvent(@Param("eventId") String eventId);
}
