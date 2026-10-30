package com.limitactivitysystem.ticketorder.controller;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.dto.TicketOrderResponse;
import com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.service.TicketOrderCloseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
class TicketOrderCancellationRequestContractTest {
    @Autowired private MockMvc mvc;
    @Autowired private JwtEncoder encoder;
    @MockitoBean private TicketOrderCloseService closing;
    @MockitoBean private AppUserMapper users;
    @MockitoBean private DataSource dataSource;

    @BeforeEach void activeBuyer() {
        AppUser user = new AppUser(); user.setId(1L); user.setRole("USER");
        given(users.findById(1L)).willReturn(user);
    }

    @Test void cancellationReturnsTheClosedStateAndReason() throws Exception {
        given(closing.cancelOrder(50L)).willReturn(new TicketOrderResponse(50L, "request", 100L, BigDecimal.TEN,
                "CLOSED", null, null, null, "USER_CANCELLED", List.of()));
        mvc.perform(post("/ticket-orders/50/cancel").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderId").value(50))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedReason").value("USER_CANCELLED"));
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void invalidPathIdsCannotReachCancellation(String id) throws Exception {
        mvc.perform(post("/ticket-orders/{id}/cancel", id).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(closing);
    }

    @ParameterizedTest @EnumSource(value = TicketOrderErrorCode.class,
            names = {"ORDER_NOT_FOUND", "ORDER_ACCESS_DENIED", "ORDER_NOT_PENDING", "USER_DISABLED"})
    void cancellationErrorsKeepTheirStatusAndCode(TicketOrderErrorCode code) throws Exception {
        given(closing.cancelOrder(50L)).willThrow(new TicketOrderException(code, "Cancellation rejected"));
        mvc.perform(post("/ticket-orders/50/cancel").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().is(code.getHttpStatus().value())).andExpect(jsonPath("$.code").value(code.name()));
    }

    @Test void anonymousCancellationIsUnauthorized() throws Exception {
        mvc.perform(post("/ticket-orders/50/cancel")).andExpect(status().isUnauthorized());
        verifyNoInteractions(closing);
    }

    private String bearer() { return "Bearer " + JwtTestTokens.token(encoder, 1L, "USER"); }
}
