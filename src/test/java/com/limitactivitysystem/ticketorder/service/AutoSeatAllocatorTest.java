package com.limitactivitysystem.ticketorder.service;

import com.limitactivitysystem.activity.model.SessionSeat;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutoSeatAllocatorTest {
    private final AutoSeatAllocator allocator = new AutoSeatAllocator();

    @Test
    void findsThreeConsecutiveSeatsInTheFirstRowRegardlessOfInputOrder() {
        var seats = List.of(seat(6, 2, 2, 1), seat(4, 1, 4, 1), seat(2, 1, 2, 1),
                seat(5, 2, 1, 1), seat(1, 1, 1, 1), seat(3, 1, 3, 1));
        assertThat(allocator.select(seats, 3, false)).extracting(SessionSeat::getSeatId).containsExactly(1L, 2L, 3L);
        assertThat(seats).extracting(SessionSeat::getSeatId).containsExactly(6L, 4L, 2L, 5L, 1L, 3L);
    }

    @Test
    void contiguousBlockStartUsesAllocationPriorityBeforeRowAndSeatNumbers() {
        var seats = List.of(seat(1, 1, 1, 5), seat(2, 1, 2, 5), seat(3, 2, 1, 1), seat(4, 2, 2, 9));
        assertThat(allocator.select(seats, 2, false)).extracting(SessionSeat::getSeatId).containsExactly(3L, 4L);
    }

    @Test
    void equalPriorityUsesRowThenSeatNumber() {
        var seats = List.of(seat(9, 2, 1, 1), seat(8, 2, 2, 1), seat(7, 1, 3, 1),
                seat(6, 1, 4, 1), seat(5, 1, 1, 1), seat(4, 1, 2, 1));
        assertThat(allocator.select(seats, 2, false)).extracting(SessionSeat::getSeatId).containsExactly(5L, 4L);
    }

    @Test
    void prefersAnAdjacentBlockEvenWhenScatteredSeatsHaveHigherPriorityAndFallbackIsAccepted() {
        var seats = List.of(seat(1, 1, 1, 1), seat(2, 1, 3, 1), seat(3, 2, 1, 2), seat(4, 2, 2, 2));
        assertThat(allocator.select(seats, 2, true)).extracting(SessionSeat::getSeatId).containsExactly(3L, 4L);
    }

    @Test
    void aGapOrRowBoundaryDoesNotCountAsAdjacency() {
        denied(List.of(seat(1, 1, 1, 1), seat(2, 1, 3, 1), seat(3, 2, 2, 1)), 2, false, "ADJACENT_SEATS_UNAVAILABLE");
    }

    @Test
    void fallbackUsesTheSamePriorityOrderingOnlyWithExplicitAcceptance() {
        var seats = List.of(seat(1, 2, 1, 2), seat(2, 1, 3, 1), seat(3, 1, 1, 1));
        assertThat(allocator.select(seats, 3, true)).extracting(SessionSeat::getSeatId).containsExactly(3L, 2L, 1L);
        denied(seats, 3, false, "ADJACENT_SEATS_UNAVAILABLE");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void insufficientAvailableQuantityFailsForEitherPreference(boolean accept) {
        denied(List.of(seat(1, 1, 1, 1)), 2, accept);
        denied(List.of(), 1, accept);
    }

    @Test
    void soldSeatsCannotCompleteAnAdjacentBlock() {
        SessionSeat sold = seat(2, 1, 2, 1);
        sold.setAllocationStatus("SOLD");
        denied(List.of(seat(1, 1, 1, 1), sold, seat(3, 1, 3, 1)), 2, false, "ADJACENT_SEATS_UNAVAILABLE");
        assertThat(allocator.select(List.of(seat(1, 1, 1, 1), sold, seat(3, 1, 3, 1)), 2, true))
                .extracting(SessionSeat::getSeatId).containsExactly(1L, 3L);
    }

    @Test
    void seatNumberArithmeticDoesNotWrapAtIntegerMax() {
        var seats = List.of(seat(1, 1, Integer.MAX_VALUE, 1), seat(2, 1, Integer.MAX_VALUE - 1, 2));
        assertThat(allocator.select(seats, 2, false)).extracting(SessionSeat::getSeatId).containsExactly(2L, 1L);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MAX_VALUE})
    void invalidOrImpossibleQuantitiesAreRejected(int quantity) {
        denied(List.of(seat(1, 1, 1, 1)), quantity, true);
    }

    private void denied(List<SessionSeat> seats, int quantity, boolean accept) {
        denied(seats, quantity, accept, "SEAT_REQUIREMENT_NOT_SATISFIED");
    }

    private void denied(List<SessionSeat> seats, int quantity, boolean accept, String code) {
        assertThatThrownBy(() -> allocator.select(seats, quantity, accept)).isInstanceOfSatisfying(
                TicketOrderException.class, exception -> assertThat(exception.getErrorCode().name())
                        .isEqualTo(code));
    }

    private SessionSeat seat(long id, int row, int number, int priority) {
        SessionSeat seat = new SessionSeat();
        seat.setSeatId(id); seat.setZoneId(10L); seat.setRowNo(row); seat.setSeatNo(number);
        seat.setAllocationPriority(priority);
        return seat;
    }
}
