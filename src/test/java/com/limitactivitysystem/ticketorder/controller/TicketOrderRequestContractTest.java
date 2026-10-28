package com.limitactivitysystem.ticketorder.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.dto.AllocationMode;
import com.limitactivitysystem.ticketorder.dto.CreateTicketOrderRequest;
import com.limitactivitysystem.ticketorder.dto.TicketOrderResponse;
import com.limitactivitysystem.ticketorder.service.TicketOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real production Controller and Security filters; the business Service is mocked for input-contract tests.
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
class TicketOrderRequestContractTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtEncoder encoder;
    @MockitoBean private TicketOrderService orders;
    private CreateTicketOrderRequest accepted;
    @MockitoBean private AppUserMapper users;
    @MockitoBean private DataSource dataSource;

    @BeforeEach
    void setUp() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setRole("USER");
        AppUser publisher = new AppUser();
        publisher.setId(2L);
        publisher.setRole("PUBLISHER");
        given(users.findById(1L)).willReturn(user);
        given(users.findById(2L)).willReturn(publisher);
        accepted = null;
        given(orders.createOrder(any())).willAnswer(invocation -> {
            accepted = invocation.getArgument(0);
            LocalDateTime created = LocalDateTime.of(2099, 1, 1, 0, 0);
            return new TicketOrderService.CreationResult(new TicketOrderResponse(50L, accepted.getRequestId(),
                    accepted.getSessionId(), BigDecimal.TEN, "PENDING_PAYMENT", created,
                    created.plusMinutes(15), null, List.of()), true);
        });
    }

    @ParameterizedTest
    @CsvSource({"USER,AUTO_ASSIGN", "PUBLISHER,AUTO_ASSIGN", "USER,SELECT_SEAT", "PUBLISHER,SELECT_SEAT"})
    void eitherConsumerRoleCanSubmitOnlyPurchaseIntent(String role, AllocationMode mode) throws Exception {
        Map<String, Object> body = request(mode);
        submit(body, role).andExpect(status().isCreated());
        assertThat(accepted.getSessionId()).isEqualTo(1001L);
        assertThat(accepted.getItems()).hasSize(1);
        var item = accepted.getItems().get(0);
        assertThat(item.getZoneId()).isEqualTo(201L);
        assertThat(item.getAllocationMode()).isEqualTo(mode);
        assertThat(item.getRequestedSeatId()).isEqualTo(mode == AllocationMode.SELECT_SEAT ? 3001L : null);
        assertThat(item.getQuantity()).isEqualTo(mode == AllocationMode.SELECT_SEAT ? 1 : 2);
        assertThat(item.getAcceptNonAdjacent()).isEqualTo(mode == AllocationMode.AUTO_ASSIGN);
    }

    @Test
    void requestIdMatchesTheDatabaseLimitWithoutRequiringUuidSyntax() throws Exception {
        var body = request(AllocationMode.AUTO_ASSIGN);
        body.put("requestId", "a".repeat(64));
        submit(body, "USER").andExpect(status().isCreated());
        assertThat(accepted.getRequestId()).isEqualTo("a".repeat(64));
    }

    @ParameterizedTest(name = "root field {0}={1}")
    @MethodSource("invalidRootFields")
    void invalidRootFieldsNeverReachTheConsumer(String field, Object value) throws Exception {
        var body = request(AllocationMode.AUTO_ASSIGN);
        body.put(field, value);
        invalid(submit(body, "USER"));
    }

    static Stream<Arguments> invalidRootFields() {
        return Stream.of(
                Arguments.of("requestId", null), Arguments.of("requestId", ""),
                Arguments.of("requestId", "  "), Arguments.of("requestId", "a".repeat(65)),
                Arguments.of("sessionId", null), Arguments.of("sessionId", 0), Arguments.of("sessionId", -1),
                Arguments.of("items", null), Arguments.of("items", List.of()),
                Arguments.of("items", Arrays.asList((Object) null)));
    }

    @ParameterizedTest(name = "item field {0}={1}")
    @MethodSource("invalidItemFields")
    void invalidNestedFieldsNeverReachTheConsumer(String field, Object value) throws Exception {
        var body = request(AllocationMode.AUTO_ASSIGN);
        item(body).put(field, value);
        invalid(submit(body, "USER"));
    }

    static Stream<Arguments> invalidItemFields() {
        return Stream.of(
                Arguments.of("zoneId", null), Arguments.of("zoneId", 0), Arguments.of("zoneId", -1),
                Arguments.of("allocationMode", null), Arguments.of("allocationMode", "UNKNOWN"),
                Arguments.of("allocationMode", "auto_assign"), Arguments.of("allocationMode", 0),
                Arguments.of("quantity", null), Arguments.of("quantity", 0), Arguments.of("quantity", -1),
                Arguments.of("acceptNonAdjacent", null), Arguments.of("requestedSeatId", 3001));
    }

    @ParameterizedTest
    @CsvSource({"requestedSeatId,null", "requestedSeatId,0", "requestedSeatId,-1", "quantity,2", "acceptNonAdjacent,true"})
    void selectedSeatRequiresOnePositiveSeatAndNoNonAdjacentOption(String field, String value) throws Exception {
        var body = request(AllocationMode.SELECT_SEAT);
        Object replacement = switch (value) {
            case "null" -> null;
            case "true" -> true;
            default -> Integer.valueOf(value);
        };
        item(body).put(field, replacement);
        invalid(submit(body, "USER"));
    }

    @ParameterizedTest(name = "{0} forbids {1}, null={2}")
    @MethodSource("forbiddenFields")
    void serverOwnedAndUnknownFieldsAreRejectedAtBothLevels(String scope, String field, boolean nullValue)
            throws Exception {
        var body = request(AllocationMode.AUTO_ASSIGN);
        Map<String, Object> target = "root".equals(scope) ? body : item(body);
        target.put(field, nullValue ? null : "forged");
        invalid(submit(body, "USER"));
    }

    static Stream<Arguments> forbiddenFields() {
        return Stream.of("root", "item").flatMap(scope -> Stream.of(
                        "buyerId", "unitPrice", "totalAmount", "status", "createdAt", "expiresAt", "paidAt",
                        "orderId", "orderItemId", "unexpected")
                .flatMap(field -> Stream.of(false, true).map(nil -> Arguments.of(scope, field, nil))));
    }

    @Test
    void anInvalidLaterItemRejectsTheWholeRequest() throws Exception {
        var body = request(AllocationMode.AUTO_ASSIGN);
        var later = new HashMap<>(item(body));
        later.put("buyerId", 99L);
        body.put("items", List.of(item(body), later));
        invalid(submit(body, "USER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"requestId", "sessionId", "items"})
    void requiredRootFieldsCannotBeOmitted(String field) throws Exception {
        var body = request(AllocationMode.AUTO_ASSIGN);
        body.remove(field);
        invalid(submit(body, "USER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"zoneId", "allocationMode", "quantity", "acceptNonAdjacent"})
    void requiredItemFieldsCannotBeOmitted(String field) throws Exception {
        var body = request(AllocationMode.AUTO_ASSIGN);
        item(body).remove(field);
        invalid(submit(body, "USER"));
    }

    @Test
    void automaticAssignmentMayExplicitlyDeclineNonAdjacentSeats() throws Exception {
        var body = request(AllocationMode.AUTO_ASSIGN);
        item(body).put("acceptNonAdjacent", false);
        item(body).put("requestedSeatId", null);
        submit(body, "USER").andExpect(status().isCreated());
        assertThat(accepted.getItems().get(0).getAcceptNonAdjacent()).isFalse();
    }

    @Test
    void authenticationIsRequiredBeforeReachingTheConsumer() throws Exception {
        mvc.perform(post("/ticket-orders").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(AllocationMode.AUTO_ASSIGN))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(accepted).isNull();
    }

    private ResultActions submit(Object body, String role) throws Exception {
        return mvc.perform(post("/ticket-orders").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + JwtTestTokens.token(encoder, "USER".equals(role) ? 1L : 2L, role))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private void invalid(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_TICKET_ORDER_REQUEST"));
        assertThat(accepted).isNull();
    }

    private Map<String, Object> request(AllocationMode mode) {
        Map<String, Object> item = new HashMap<>();
        item.put("zoneId", 201);
        item.put("allocationMode", mode.name());
        item.put("quantity", mode == AllocationMode.SELECT_SEAT ? 1 : 2);
        item.put("acceptNonAdjacent", mode == AllocationMode.AUTO_ASSIGN);
        if (mode == AllocationMode.SELECT_SEAT) {
            item.put("requestedSeatId", 3001);
        }
        return new HashMap<>(Map.of("requestId", "b3b72e2d-73e9-42de-8a05-3d6df4621b6c",
                "sessionId", 1001, "items", List.of(item)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> item(Map<String, Object> body) {
        return ((List<Map<String, Object>>) body.get("items")).get(0);
    }

}
