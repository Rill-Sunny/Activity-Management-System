package com.limitactivitysystem.outbox;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Mapper
public interface OutboxEventMapper {
    @Insert("""
            INSERT INTO outbox_event (event_id, event_type, aggregate_id, payload, status, traceparent)
            VALUES (#{eventId}, #{eventType}, #{aggregateId}, CAST(#{payload} AS JSON), 'PENDING', #{traceparent})
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int insert(@Param("eventId") String eventId, @Param("eventType") String eventType,
               @Param("aggregateId") Long aggregateId, @Param("payload") String payload,
               @Param("traceparent") String traceparent);

    @Update("""
            UPDATE outbox_event
            SET status = 'FAILED', lease_until = NULL, lease_token = NULL,
                failure_reason = COALESCE(failure_reason, 'Claim lease expired after maximum attempts')
            WHERE status = 'CLAIMED' AND lease_until <= CURRENT_TIMESTAMP(3) AND attempts >= #{maxAttempts}
            """)
    int failExhaustedLeases(@Param("maxAttempts") int maxAttempts);

    @Select("""
            SELECT event_id, event_type, aggregate_id, payload, status, attempts, lease_until, lease_token, traceparent
            FROM outbox_event
            WHERE (status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(3) AND attempts < #{maxAttempts})
               OR (status = 'CLAIMED' AND lease_until <= CURRENT_TIMESTAMP(3) AND attempts < #{maxAttempts})
            ORDER BY created_at, event_id
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    @Results(id = "outboxEventResult", value = {
            @Result(column = "event_id", property = "eventId", id = true),
            @Result(column = "event_type", property = "eventType"),
            @Result(column = "aggregate_id", property = "aggregateId"),
            @Result(column = "payload", property = "payload"),
            @Result(column = "status", property = "status"),
            @Result(column = "attempts", property = "attempts"),
            @Result(column = "lease_until", property = "leaseUntil"),
            @Result(column = "lease_token", property = "leaseToken"),
            @Result(column = "traceparent", property = "traceparent")
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Transactional(propagation = Propagation.MANDATORY)
    List<OutboxEvent> findClaimable(@Param("limit") int limit, @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE outbox_event
            SET status = 'CLAIMED', attempts = attempts + 1,
                lease_until = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL #{leaseSeconds} SECOND),
                lease_token = #{leaseToken}
            WHERE event_id = #{eventId}
              AND ((status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP(3))
                OR (status = 'CLAIMED' AND lease_until <= CURRENT_TIMESTAMP(3)))
              AND attempts < #{maxAttempts}
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int claim(@Param("eventId") String eventId, @Param("leaseToken") String leaseToken,
              @Param("leaseSeconds") int leaseSeconds, @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE outbox_event
            SET status = 'SENT', published_at = CURRENT_TIMESTAMP(3),
                lease_until = NULL, lease_token = NULL, failure_reason = NULL
            WHERE event_id = #{eventId} AND status = 'CLAIMED' AND lease_token = #{leaseToken}
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int markSent(@Param("eventId") String eventId, @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE outbox_event
            SET status = CASE WHEN attempts >= #{maxAttempts} THEN 'FAILED' ELSE 'PENDING' END,
                next_attempt_at = CASE WHEN attempts >= #{maxAttempts} THEN next_attempt_at
                    ELSE DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL #{retryDelaySeconds} SECOND) END,
                lease_until = NULL, lease_token = NULL, failure_reason = #{failureReason}
            WHERE event_id = #{eventId} AND status = 'CLAIMED' AND lease_token = #{leaseToken}
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int markPublishFailed(@Param("eventId") String eventId, @Param("leaseToken") String leaseToken,
                          @Param("maxAttempts") int maxAttempts, @Param("retryDelaySeconds") int retryDelaySeconds,
                          @Param("failureReason") String failureReason);

    @Select("SELECT EXISTS(SELECT 1 FROM consumer_process WHERE event_id = #{eventId})")
    boolean hasBeenProcessed(@Param("eventId") String eventId);

    @Update("""
            UPDATE outbox_event
            SET status = 'PENDING', attempts = 0, next_attempt_at = CURRENT_TIMESTAMP(3),
                lease_until = NULL, lease_token = NULL, failure_reason = NULL, published_at = NULL
            WHERE event_id = #{eventId}
              AND (status = 'FAILED' OR (status = 'SENT' AND EXISTS (
                  SELECT 1 FROM consumer_failure WHERE event_id = #{eventId} AND status = 'FAILED'
              )))
            """)
    @Transactional(propagation = Propagation.MANDATORY)
    int requestReplay(@Param("eventId") String eventId);
}
