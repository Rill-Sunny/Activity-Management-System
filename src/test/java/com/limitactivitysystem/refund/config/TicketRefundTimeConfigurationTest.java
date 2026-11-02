package com.limitactivitysystem.refund.config;

import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class TicketRefundTimeConfigurationTest {
    @Test void configuredClockUsesTheDatabaseEvenWhenItDiffersFromTheJvmWallClock() {
        var reads = new AtomicInteger();
        var database = LocalDateTime.of(2037, 1, 2, 12, 30, 0, 478_000_000);
        DatabaseTimeMapper source = () -> { reads.incrementAndGet(); return database; };
        Clock clock = new TicketRefundTimeConfiguration().ticketRefundClock("Asia/Shanghai", source);
        assertThat(LocalDateTime.now(clock)).isEqualTo(database);
        assertThat(reads).hasValue(1);
    }
    @Test void changingTheDisplayZonePreservesTheDatabaseInstant() {
        var database = LocalDateTime.of(2037, 1, 2, 12, 30);
        Clock clock = new DatabaseRefundClock(() -> database, ZoneId.of("Asia/Shanghai"));
        assertThat(clock.withZone(ZoneOffset.UTC).instant()).isEqualTo(database.toInstant(ZoneOffset.ofHours(8)));
        assertThat(LocalDateTime.now(clock.withZone(ZoneOffset.UTC))).isEqualTo(database.minusHours(8));
    }
    @Test void laterAttemptsReadFreshTimeAndDatabaseFailuresPropagate() {
        var reads = new AtomicInteger();
        Clock clock = new DatabaseRefundClock(() -> {
            if (reads.incrementAndGet() == 2) { throw new IllegalStateException("database unavailable"); }
            return LocalDateTime.of(2037, 1, 2, 12, 30);
        }, ZoneOffset.UTC);
        clock.instant();
        assertThatThrownBy(clock::instant).isInstanceOf(IllegalStateException.class).hasMessage("database unavailable");
        assertThat(reads).hasValue(2);
    }
}
