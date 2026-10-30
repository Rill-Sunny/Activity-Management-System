package com.limitactivitysystem.reservation.controller;

import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.reservation.dto.ReservationResponse;
import com.limitactivitysystem.reservation.exception.ReservationErrorCode;
import com.limitactivitysystem.reservation.exception.ReservationException;
import com.limitactivitysystem.reservation.service.FreeReservationService;
import com.limitactivitysystem.security.JwtTestTokens;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.LocalDateTime;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
class ReservationRequestContractTest {
    @Autowired private MockMvc mvc;
    @Autowired private JwtEncoder encoder;
    @MockitoBean private FreeReservationService reservations;
    @MockitoBean private AppUserMapper users;
    @MockitoBean private DataSource dataSource;

    @BeforeEach void consumers() {
        for (long id = 1; id <= 2; id++) {
            var user = new AppUser(); user.setId(id); user.setRole(id == 1 ? "USER" : "PUBLISHER");
            given(users.findById(id)).willReturn(user);
        }
    }

    @ParameterizedTest @CsvSource({"USER,false", "USER,true", "PUBLISHER,false", "PUBLISHER,true"})
    void eitherRoleCreatesAnImmediateActiveReservation(String role, boolean emptyBody) throws Exception {
        var now = LocalDateTime.of(2026, 10, 28, 19, 58);
        given(reservations.create(10L)).willReturn(new ReservationResponse(50L, role.equals("USER") ? 1L : 2L, 10L, "ACTIVE", now, null));
        var request = post("/sessions/10/reservations").header(HttpHeaders.AUTHORIZATION, bearer(role));
        if (emptyBody) { request.contentType(MediaType.APPLICATION_JSON).content("{}"); }
        mvc.perform(request).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.reservationId").value(50)).andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.expiresAt").doesNotExist()).andExpect(jsonPath("$.orderId").doesNotExist());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cancellationReturnsTheOriginalIdAndRecordedCancellationTime(boolean emptyBody) throws Exception {
        var now = LocalDateTime.of(2026, 10, 28, 19, 58);
        given(reservations.cancel(50L)).willReturn(new ReservationResponse(50L, 1L, 10L, "CANCELLED", now, now.plusMinutes(1)));
        var request = post("/reservations/50/cancel").header(HttpHeaders.AUTHORIZATION, bearer("USER"));
        if (emptyBody) { request.contentType(MediaType.APPLICATION_JSON).content("{}"); }
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.reservationId").value(50))
                .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.cancelledAt").exists());
    }

    @ParameterizedTest @ValueSource(strings = {"userId", "sessionId", "quantity", "status", "createdAt", "cancelledAt", "expiresAt", "reservationId"})
    void clientFieldsCannotReachEitherBusinessOperation(String field) throws Exception {
        for (String path : paths()) {
            mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer("USER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"" + field + "\":null}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESERVATION_REQUEST"));
        }
        verifyNoInteractions(reservations);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void malformedIdsCannotReachTheService(String id) throws Exception {
        for (String path : new String[]{"/sessions/" + id + "/reservations", "/reservations/" + id + "/cancel"}) {
            mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer("USER"))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(reservations);
    }

    @ParameterizedTest @ValueSource(strings = {"[]", "{", "\"ACTIVE\""})
    void malformedBodiesAreRejected(String body) throws Exception {
        for (String path : paths()) {
            mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer("USER"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(reservations);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anonymousAndDisabledAccountsCannotWrite(boolean disabled) throws Exception {
        var user = new AppUser(); user.setId(1L); user.setStatus("DISABLED");
        given(users.findById(1L)).willReturn(user);
        for (String path : paths()) {
            var request = post(path);
            if (disabled) { request.header(HttpHeaders.AUTHORIZATION, bearer("USER")); }
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(reservations);
    }

    @ParameterizedTest @EnumSource(ReservationErrorCode.class)
    void businessFailuresUseStableHttpCodes(ReservationErrorCode code) throws Exception {
        given(reservations.create(10L)).willThrow(new ReservationException(code, "Rejected"));
        mvc.perform(post("/sessions/10/reservations").header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().is(code.getHttpStatus().value())).andExpect(jsonPath("$.code").value(code.name()));
    }

    private String[] paths() { return new String[]{"/sessions/10/reservations", "/reservations/50/cancel"}; }
    private String bearer(String role) { return "Bearer " + JwtTestTokens.token(encoder, role.equals("USER") ? 1L : 2L, role); }
}
