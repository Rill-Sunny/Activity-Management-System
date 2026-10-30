package com.limitactivitysystem.refund.service;

import com.limitactivitysystem.refund.model.TicketRefundDecision;
import com.limitactivitysystem.refund.exception.TicketRefundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.*;

class OrdinaryTicketRefundPolicyTest {
    private final OrdinaryTicketRefundPolicy policy = new OrdinaryTicketRefundPolicy();
    private final LocalDateTime start = LocalDateTime.of(2027, 6, 30, 20, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @ParameterizedTest
    @CsvSource({"30,-1,0.0000", "30,0,0.1500", "30,1,0.1500",
            "14,-1,0.1500", "14,0,0.4000", "14,1,0.4000",
            "10,-1,0.4000", "10,0,REFUND_WINDOW_CLOSED", "10,1,REFUND_WINDOW_CLOSED",
            "0,-1,REFUND_WINDOW_CLOSED", "0,0,REFUND_SESSION_STARTED", "0,1,REFUND_SESSION_STARTED"})
    void exactDayBoundariesUseTheApplicationTime(int days, int millis, String expected) {
        check(start.minusDays(days).plusNanos(millis * 1_000_000L), start.minusDays(90), start, expected);
    }

    @ParameterizedTest @CsvSource({"-1,0.0000", "0,0.4000", "1,0.4000"})
    void theFreeWindowEndsExactlyFortyEightHoursAfterActivityOpening(int millis, String expected) {
        var open = start.minusDays(15);
        check(open.plusHours(48).plusNanos(millis * 1_000_000L), open, start, expected);
    }

    @ParameterizedTest @CsvSource({"-1,0.0000", "0,REFUND_SESSION_STARTED", "1,REFUND_SESSION_STARTED"})
    void startTimeOverridesTheFreeWindow(int millis, String expected) {
        check(start.plusNanos(millis * 1_000_000L), start.minusHours(1), start, expected);
    }

    @ParameterizedTest @CsvSource({"10.01,20,8.51", "20.03,20,17.03", "0.10,20,0.09",
            "10.01,12,6.01", "0.01,12,0.01", "10.01,40,10.01", "399.00,20,339.15"})
    void moneyIsRoundedHalfUpForEachTicket(String price, int daysUntilStart, String expected) {
        var requested = fixedTime(start.minusDays(daysUntilStart));
        var result = policy.evaluate(requested, start.minusDays(90), start, new BigDecimal(price));
        assertThat(result.allowed()).isTrue();
        assertThat(result.refundAmount()).isEqualByComparingTo(expected).hasScaleOf(2);
        assertThat(result.feeAmount().add(result.refundAmount())).isEqualByComparingTo(price);
        if (price.equals("399.00")) {
            assertThat(result.feeRate()).isEqualByComparingTo("0.15");
            assertThat(result.refundRate()).isEqualByComparingTo("0.85");
            assertThat(result.feeAmount()).isEqualByComparingTo("59.85");
        }
    }

    @Test void theOpeningPromotionIsNotExtendedByALaterPayment() {
        var open = LocalDateTime.of(2026, 10, 1, 20, 0);
        var sessionStart = LocalDateTime.of(2026, 10, 15, 20, 0);
        // A purchase on Oct 2 at 18:00 does not extend the activity's Oct 3 at 20:00 cutoff.
        check(LocalDateTime.of(2026, 10, 3, 19, 30), open, sessionStart, "0.0000");
        check(LocalDateTime.of(2026, 10, 3, 20, 0), open, sessionStart, "0.4000");
        check(LocalDateTime.of(2026, 10, 3, 20, 30), open, sessionStart, "0.4000");
    }

    @ParameterizedTest @CsvSource({"0", "-1", "1.001"})
    void invalidTicketPricesCannotProduceAQuote(String price) {
        assertThatThrownBy(() -> policy.evaluate(start.minusDays(20), start.minusDays(90), start, new BigDecimal(price)))
                .isInstanceOfSatisfying(TicketRefundException.class, failure -> {
                    assertThat(failure.getErrorCode().name()).isEqualTo("REFUND_AMOUNT_CALCULATION_ERROR");
                    assertThat(failure.getErrorCode().getHttpStatus().value()).isEqualTo(500);
                });
    }

    @Test void quotesAreIndependentOfEvaluationOrder() {
        var first = policy.evaluate(fixedTime(start.minusDays(20)), start.minusDays(90), start, new BigDecimal("399.00"));
        var denied = policy.evaluate(fixedTime(start), start.minusDays(90), start, new BigDecimal("399.00"));
        assertThat(denied.allowed()).isFalse();
        assertThat(policy.evaluate(fixedTime(start.minusDays(20)), start.minusDays(90), start, new BigDecimal("399.00")))
                .isEqualTo(first);
    }

    private void check(LocalDateTime requested, LocalDateTime open, LocalDateTime sessionStart, String expected) {
        TicketRefundDecision result = policy.evaluate(fixedTime(requested), open, sessionStart, new BigDecimal("399.00"));
        if (expected.startsWith("REFUND_")) {
            assertThat(result.allowed()).isFalse();
            assertThat(result.rejectionReason().name()).isEqualTo(expected);
            assertThat(result.feeRate()).isNull(); assertThat(result.refundRate()).isNull();
            assertThat(result.refundAmount()).isNull(); assertThat(result.feeAmount()).isNull();
        } else {
            assertThat(result.allowed()).isTrue(); assertThat(result.rejectionReason()).isNull();
            assertThat(result.feeRate()).isEqualByComparingTo(expected);
            switch (expected) {
                case "0.0000" -> {
                    assertThat(result.refundRate()).isEqualByComparingTo("1");
                    assertThat(result.refundAmount()).isEqualByComparingTo("399.00");
                }
                case "0.1500" -> {
                    assertThat(result.refundRate()).isEqualByComparingTo("0.85");
                    assertThat(result.refundAmount()).isEqualByComparingTo("339.15");
                }
                case "0.4000" -> {
                    assertThat(result.refundRate()).isEqualByComparingTo("0.60");
                    assertThat(result.refundAmount()).isEqualByComparingTo("239.40");
                }
                default -> throw new IllegalArgumentException(expected);
            }
        }
    }

    private LocalDateTime fixedTime(LocalDateTime value) {
        Clock clock = Clock.fixed(value.atZone(ZONE).toInstant(), ZONE);
        return LocalDateTime.now(clock);
    }
}
