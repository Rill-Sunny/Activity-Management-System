package com.limitactivitysystem.ticketorder.controller;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.dto.TicketPaymentResponse;
import com.limitactivitysystem.ticketorder.dto.TicketResponse;
import com.limitactivitysystem.ticketorder.exception.TicketOrderErrorCode;
import com.limitactivitysystem.ticketorder.exception.TicketOrderException;
import com.limitactivitysystem.ticketorder.service.TicketPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
class TicketPaymentRequestContractTest {
    @Autowired private MockMvc mvc;
    @Autowired private JwtEncoder encoder;
    @MockitoBean private TicketPaymentService payments;
    @MockitoBean private AppUserMapper users;
    @MockitoBean private DataSource dataSource;

    @BeforeEach void authenticatedConsumerAccounts() {
        for (long id : List.of(1L, 2L)) {
            AppUser user = new AppUser(); user.setId(id); user.setRole(id == 1 ? "USER" : "PUBLISHER");
            given(users.findById(id)).willReturn(user);
        }
    }

    @ParameterizedTest @CsvSource({"USER,false", "USER,true", "PUBLISHER,false", "PUBLISHER,true"})
    void eitherConsumerRolePaysWithNoFields(String role, boolean emptyObject) throws Exception {
        var now = LocalDateTime.of(2026, 10, 26, 20, 0);
        given(payments.pay(50L)).willReturn(new TicketPaymentResponse(50L, 100L, new BigDecimal("29.90"), "PAID", now,
                List.of(new TicketResponse(1L, 2L, 3L, new BigDecimal("29.90"), "VALID", now))));
        var request = post("/ticket-orders/50/pay").header(HttpHeaders.AUTHORIZATION, bearer(role));
        if (emptyObject) { request.contentType(MediaType.APPLICATION_JSON).content("{}"); }
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.totalAmount").value(29.90)).andExpect(jsonPath("$.tickets[0].seatId").value(3));
        verify(payments).pay(50L);
    }

    @ParameterizedTest @ValueSource(strings = {"buyerId", "totalAmount", "paidAt", "status", "ticketId", "seatId", "quantity", "unitPrice"})
    void noClientProvidedFieldCanReachPayment(String field) throws Exception {
        mvc.perform(post("/ticket-orders/50/pay").header(HttpHeaders.AUTHORIZATION, bearer("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"" + field + "\":null}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAYMENT_REQUEST"));
        verifyNoInteractions(payments);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void invalidPathIdsAreRejected(String orderId) throws Exception {
        mvc.perform(post("/ticket-orders/{id}/pay", orderId).header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(payments);
    }

    @ParameterizedTest @ValueSource(strings = {"[]", "{", "\"paid\""})
    void malformedOrNonObjectJsonCannotReachPayment(String body) throws Exception {
        mvc.perform(post("/ticket-orders/50/pay").header(HttpHeaders.AUTHORIZATION, bearer("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(payments);
    }

    @Test void anonymousPaymentIsUnauthorized() throws Exception {
        mvc.perform(post("/ticket-orders/50/pay")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(payments);
    }

    @Test void anOldTokenFromADisabledAccountIsUnauthorized() throws Exception {
        AppUser disabled = new AppUser(); disabled.setId(1L); disabled.setRole("USER"); disabled.setStatus("DISABLED");
        given(users.findById(1L)).willReturn(disabled);
        mvc.perform(post("/ticket-orders/50/pay").header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(payments);
    }

    @ParameterizedTest @EnumSource(value = TicketOrderErrorCode.class, names = {"ORDER_NOT_FOUND", "ORDER_ACCESS_DENIED",
            "ORDER_NOT_PENDING", "ORDER_EXPIRED", "USER_DISABLED", "SESSION_NOT_AVAILABLE", "PURCHASE_LIMIT_EXCEEDED",
            "INSUFFICIENT_INVENTORY", "SEAT_NOT_AVAILABLE", "ADJACENT_SEATS_UNAVAILABLE", "INVALID_SEAT_RELATION", "ORDER_PAYMENT_CONFLICT"})
    void paymentErrorsUseTheirStableContract(TicketOrderErrorCode code) throws Exception {
        given(payments.pay(50L)).willThrow(new TicketOrderException(code, "Payment rejected"));
        mvc.perform(post("/ticket-orders/50/pay").header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().is(code.getHttpStatus().value())).andExpect(jsonPath("$.code").value(code.name()));
    }

    private String bearer(String role) { return "Bearer " + JwtTestTokens.token(encoder, role.equals("USER") ? 1L : 2L, role); }
}
