package com.limitactivitysystem.refund.config;

import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** Uses the same database time source as ticket issuance, before application row locks. */
public final class DatabaseRefundClock extends Clock {
    private final DatabaseTimeMapper time;
    private final ZoneId databaseZone;
    private final ZoneId zone;

    public DatabaseRefundClock(DatabaseTimeMapper time, ZoneId databaseZone) {
        this(time, databaseZone, databaseZone);
    }
    private DatabaseRefundClock(DatabaseTimeMapper time, ZoneId databaseZone, ZoneId zone) {
        this.time = Objects.requireNonNull(time);
        this.databaseZone = Objects.requireNonNull(databaseZone);
        this.zone = Objects.requireNonNull(zone);
    }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId zone) { return new DatabaseRefundClock(time, databaseZone, zone); }
    @Override public Instant instant() { return time.currentTime().atZone(databaseZone).toInstant(); }
}
