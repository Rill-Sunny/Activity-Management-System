package com.limitactivitysystem.reschedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class ReschedulePolicyTest {
    private final ReschedulePolicy policy=new ReschedulePolicy("Asia/Shanghai");
    @ParameterizedTest
    @CsvSource({"2026-11-25T19:30,2026-11-17T10:00","2026-11-12T19:30,2026-11-12T19:30"})
    void at17EarlierOfSevenDaysAndNewStart(String start,String expected) {
        assertThat(policy.deadline(LocalDateTime.parse("2026-11-10T10:00"),LocalDateTime.parse(start)))
                .isEqualTo(LocalDateTime.parse(expected));
    }
    @ParameterizedTest
    @CsvSource({"-1,true","0,false","1,false"})
    void exclusiveDeadlineAtMillisecondPrecision(long milliseconds,boolean accepted) {
        var announced=LocalDateTime.parse("2026-11-10T10:00"); var deadline=announced.plusDays(7);
        assertThat(policy.accepts(announced,deadline,deadline.plusDays(1),deadline.plusNanos(milliseconds*1_000_000)))
                .isEqualTo(accepted);
    }
    @Test void beforeAnnouncementAndCurrentSessionStartAreRejected() {
        var announced=LocalDateTime.parse("2026-11-10T10:00");
        assertThat(policy.accepts(announced,announced.plusDays(7),announced.plusDays(10),announced.minusNanos(1))).isFalse();
        assertThat(policy.accepts(announced,announced.plusDays(7),announced.plusDays(1),announced.plusDays(1))).isFalse();
    }
    @Test void offsetConversionPreservesInstantAndMilliseconds() {
        assertThat(policy.local(OffsetDateTime.parse("2026-12-18T11:30:00.123456Z")))
                .isEqualTo(LocalDateTime.parse("2026-12-18T19:30:00.123"));
    }
}
