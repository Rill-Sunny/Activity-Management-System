package com.limitactivitysystem.activity.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.activity.dto.CreateZoneSeatsRequest;
import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.mapper.SessionSeatMapper;
import com.limitactivitysystem.activity.mapper.SessionZoneMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.activity.model.SessionSeat;
import com.limitactivitysystem.activity.model.SessionZone;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.AppUserJwtAuthenticationConverter;
import com.limitactivitysystem.security.JwtTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Tests deliberately have no surrounding transaction: HTTP/service transactions must really commit or roll back.
abstract class ActivityMySqlTestSupport {

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected ActivityMapper activityMapper;
    @Autowired protected ActivitySessionMapper sessionMapper;
    @Autowired protected SessionZoneMapper zoneMapper;
    @Autowired protected SessionSeatMapper seatMapper;
    @Autowired protected AppUserMapper userMapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected JwtEncoder jwtEncoder;
    @Autowired protected JwtDecoder jwtDecoder;
    @Autowired protected AppUserJwtAuthenticationConverter authenticationConverter;
    @Autowired private PasswordEncoder passwordEncoder;

    protected AppUser publisher;
    protected AppUser otherPublisher;
    protected AppUser buyer;
    private final List<Long> fixtureUserIds = new ArrayList<>();

    @BeforeEach
    void insertTestUsers() {
        String hash = passwordEncoder.encode("day3-integration-test-password");
        publisher = insertUser("PUBLISHER", hash);
        otherPublisher = insertUser("PUBLISHER", hash);
        buyer = insertUser("USER", hash);
    }

    @AfterEach
    void deleteOnlyOwnedTestFixtures() {
        SecurityContextHolder.clearContext();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        if (fixtureUserIds.isEmpty()) {
            return;
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            for (Long ownerId : fixtureUserIds) {
                jdbc.update("""
                        DELETE st FROM session_seat st
                        JOIN session_zone z ON z.zone_id = st.zone_id
                        JOIN activity_session s ON s.activity_session_id = z.session_id
                        JOIN activity a ON a.activity_id = s.activity_id WHERE a.publisher_id = ?
                        """, ownerId);
                jdbc.update("""
                        DELETE z FROM session_zone z
                        JOIN activity_session s ON s.activity_session_id = z.session_id
                        JOIN activity a ON a.activity_id = s.activity_id WHERE a.publisher_id = ?
                        """, ownerId);
                jdbc.update("""
                        DELETE s FROM activity_session s
                        JOIN activity a ON a.activity_id = s.activity_id WHERE a.publisher_id = ?
                        """, ownerId);
                jdbc.update("DELETE FROM activity WHERE publisher_id = ?", ownerId);
                jdbc.update("DELETE FROM app_user WHERE id = ?", ownerId);
            }
        });
    }

    protected Activity insertDraft(AppUser owner, String type) {
        Activity activity = new Activity();
        activity.setPublisherId(owner.getId());
        activity.setName("Day 3 integration fixture");
        activity.setDescription("Only this test's fixtures are removed");
        activity.setType(type);
        activity.setOpenTime(LocalDateTime.of(2099, 11, 1, 9, 0));
        activity.setCloseTime(LocalDateTime.of(2099, 11, 10, 18, 0));
        assertThat(activityMapper.insert(activity)).isEqualTo(1);
        assertThat(activity.getActivityId()).isPositive();
        return activity;
    }

    protected ActivitySession insertSession(Activity activity) {
        ActivitySession session = new ActivitySession();
        session.setActivityId(activity.getActivityId());
        session.setStartTime(activity.getCloseTime().plusDays(1));
        session.setEndTime(session.getStartTime().plusHours(2));
        session.setCapacity(10);
        if ("PAID".equals(activity.getType())) {
            session.setMaxTicketsPerUser(2);
            session.setSeatMode("SELECT_SEAT");
        }
        persistSession(session);
        return session;
    }

    protected void persistSession(ActivitySession session) {
        assertThat(sessionMapper.insert(session)).isEqualTo(1);
        assertThat(session.getActivitySessionId()).isPositive();
    }

    protected SessionZone insertZone(ActivitySession session, String type, int planned, int configuredSeats) {
        SessionZone zone = new SessionZone();
        zone.setSessionId(session.getActivitySessionId());
        zone.setName(type + "_" + UUID.randomUUID());
        zone.setZoneType(type);
        zone.setPrice("GIFT".equals(type) ? BigDecimal.ZERO : new BigDecimal("10.00"));
        zone.setTotalAmount(planned);
        zone.setRemainsAmount(planned);
        assertThat(zoneMapper.insert(zone)).isEqualTo(1);
        assertThat(zone.getZoneId()).isPositive();
        insertSeats(zone.getZoneId(), configuredSeats);
        return zone;
    }

    protected void insertSeats(Long zoneId, int count) {
        if (count == 0) {
            return;
        }
        List<SessionSeat> seats = new ArrayList<>();
        for (int position = 1; position <= count; position++) {
            SessionSeat seat = new SessionSeat();
            seat.setZoneId(zoneId);
            seat.setRowNo(1);
            seat.setSeatNo(position);
            seat.setAllocationPriority(position);
            seats.add(seat);
        }
        assertThat(seatMapper.batchInsert(seats)).isEqualTo(count);
        assertThat(seats).allSatisfy(seat -> assertThat(seat.getSeatId()).isPositive());
    }

    protected CreateZoneSeatsRequest oneSeatRequest(int position) {
        CreateZoneSeatsRequest.SeatDefinition seat = new CreateZoneSeatsRequest.SeatDefinition();
        seat.setRowNo(1);
        seat.setSeatNo(position);
        seat.setAllocationPriority(position);
        CreateZoneSeatsRequest request = new CreateZoneSeatsRequest();
        request.setSeats(List.of(seat));
        return request;
    }

    protected String bearer(AppUser user) {
        return "Bearer " + JwtTestTokens.token(jwtEncoder, user.getId(), user.getRole());
    }

    protected ResultActions postJson(String path, AppUser user, Object body) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    protected JsonNode create(String path, Object body) throws Exception {
        String response = postJson(path, publisher, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    protected ResultActions publish(Activity activity, AppUser user) throws Exception {
        return mvc.perform(post("/publisher/activities/{id}/publish", activity.getActivityId())
                .header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    protected Map<String, Object> activityBody(String type) {
        return Map.of("name", "Day 3 HTTP fixture", "description", "demo", "type", type,
                "openTime", "2099-11-01T09:00:00", "closeTime", "2099-11-10T18:00:00");
    }

    protected Map<String, Object> sessionBody() {
        return Map.of("startTime", "2099-11-11T18:00:00", "endTime", "2099-11-11T20:00:00",
                "capacity", 10, "maxTicketsPerUser", 2, "seatMode", "SELECT_SEAT");
    }

    protected <T> T asPublisher(Callable<T> operation) throws Exception {
        var jwt = jwtDecoder.decode(JwtTestTokens.token(jwtEncoder, publisher.getId(), publisher.getRole()));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticationConverter.convert(jwt));
        SecurityContextHolder.setContext(context);
        try {
            return operation.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private AppUser insertUser(String role, String hash) {
        AppUser user = new AppUser();
        user.setUsername("day3_" + UUID.randomUUID().toString().replace("-", ""));
        user.setPasswordHash(hash);
        user.setRole(role);
        assertThat(userMapper.insert(user)).isEqualTo(1);
        assertThat(user.getId()).isPositive();
        fixtureUserIds.add(user.getId());
        return user;
    }
}
