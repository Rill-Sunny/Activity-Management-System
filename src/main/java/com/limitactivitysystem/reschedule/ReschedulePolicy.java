package com.limitactivitysystem.reschedule;

import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.limitactivitysystem.refund.config.DatabaseRefundClock;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;

@Configuration(proxyBeanMethods=false)
public class ReschedulePolicy {
    private final ZoneId zone;
    public ReschedulePolicy(@Value("${ticket-refund.time-zone:Asia/Shanghai}") String zone) {
        this.zone = ZoneId.of(zone);
    }
    @Bean("rescheduleClock")
    public Clock rescheduleClock(DatabaseTimeMapper time) { return new DatabaseRefundClock(time, zone); }
    public LocalDateTime local(OffsetDateTime value) {
        return value.atZoneSameInstant(zone).toLocalDateTime().truncatedTo(ChronoUnit.MILLIS);
    }
    public OffsetDateTime offset(LocalDateTime value) { return value.atZone(zone).toOffsetDateTime(); }
    public LocalDateTime deadline(LocalDateTime announced, LocalDateTime start) {
        LocalDateTime week = announced.atZone(zone).plusHours(7 * 24).toLocalDateTime();
        return week.isBefore(start) ? week : start;
    }
    public boolean accepts(LocalDateTime announced, LocalDateTime deadline, LocalDateTime currentStart, LocalDateTime now) {
        return !now.isBefore(announced) && now.isBefore(deadline) && now.isBefore(currentStart);
    }
}
