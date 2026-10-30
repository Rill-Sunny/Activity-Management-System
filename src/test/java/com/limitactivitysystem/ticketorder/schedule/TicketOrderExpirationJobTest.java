package com.limitactivitysystem.ticketorder.schedule;

import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.service.TicketOrderCloseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketOrderExpirationJobTest {
    @Mock private TicketOrderMapper orders;
    @Mock private TicketOrderCloseService closing;

    @Test void failedAndStaleCandidatesDoNotStopTheBatchAndCanBeRetriedNextTime() {
        var job = new TicketOrderExpirationJob(orders, closing, 3);
        given(orders.findExpiredPendingOrderIds(3)).willReturn(List.of(1L, 2L, 3L), List.of(1L));
        given(closing.closeExpiredOrder(1L)).willThrow(new DataAccessResourceFailureException("Temporary failure"))
                .willReturn(true);
        given(closing.closeExpiredOrder(2L)).willReturn(false);
        given(closing.closeExpiredOrder(3L)).willReturn(true);

        job.closeExpiredOrders();
        job.closeExpiredOrders();

        var sequence = inOrder(orders, closing);
        sequence.verify(orders).findExpiredPendingOrderIds(3);
        sequence.verify(closing).closeExpiredOrder(1L);
        sequence.verify(closing).closeExpiredOrder(2L);
        sequence.verify(closing).closeExpiredOrder(3L);
        sequence.verify(orders).findExpiredPendingOrderIds(3);
        sequence.verify(closing).closeExpiredOrder(1L);
        sequence.verifyNoMoreInteractions();
    }

    @Test void emptyBatchDoesNoWork() {
        given(orders.findExpiredPendingOrderIds(10)).willReturn(List.of());
        new TicketOrderExpirationJob(orders, closing, 10).closeExpiredOrders();
        verifyNoInteractions(closing);
    }

    @Test void nonPositiveBatchSizeFailsBeforeAnyScan() {
        assertThatThrownBy(() -> new TicketOrderExpirationJob(orders, closing, 0))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orders, closing);
    }
}
