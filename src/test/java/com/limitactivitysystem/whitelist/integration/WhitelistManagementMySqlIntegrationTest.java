package com.limitactivitysystem.whitelist.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.whitelist.service.QualificationService;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Real Security filters, MyBatis and MySQL, with real service commits/rollbacks and no outer test transaction.
// A dedicated connection keeps V3 temporary tables visible across sequential service transactions.
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(WhitelistManagementMySqlIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class WhitelistManagementMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtEncoder encoder;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AppUserMapper userMapper;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private ActivitySessionMapper sessionMapper;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DeleteFailure failure;
    @Autowired private QualificationService qualificationService;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> activityIds = new ArrayList<>();
    private final List<Long> sessionIds = new ArrayList<>();
    private AppUser publisher;
    private AppUser otherPublisher;
    private AppUser buyer;
    private Activity activity;
    private ActivitySession session;
    private Activity otherActivity;
    private ActivitySession otherSession;

    @BeforeEach
    void createTemporaryWhitelistTablesAndOwnedFixtures() throws Exception {
        String ddl;
        try (var input = new ClassPathResource("db/migration/V3__create_whitelist_tables.sql").getInputStream()) {
            ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ByteArrayResource(
                    ddl.replace("CREATE TABLE ", "CREATE TEMPORARY TABLE ").getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8));
        }
        String hash = passwordEncoder.encode("whitelist-integration-password");
        publisher = insertUser("PUBLISHER", hash);
        otherPublisher = insertUser("PUBLISHER", hash);
        buyer = insertUser("USER", hash);
        activity = insertActivity(publisher.getId());
        session = insertSession(activity.getActivityId());
        otherActivity = insertActivity(otherPublisher.getId());
        otherSession = insertSession(otherActivity.getActivityId());
    }

    @AfterEach
    void removeOnlyThisTestsData() {
        failure.armed.set(false);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        // TEMPORARY prevents a failed setup from dropping a permanent table with the same name.
        jdbc.execute("DROP TEMPORARY TABLE IF EXISTS whitelist_member");
        jdbc.execute("DROP TEMPORARY TABLE IF EXISTS whitelist_config");
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            sessionIds.forEach(id -> jdbc.update("DELETE FROM activity_session WHERE activity_session_id = ?", id));
            activityIds.forEach(id -> jdbc.update("DELETE FROM activity WHERE activity_id = ?", id));
            userIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
        });
    }

    @ParameterizedTest
    @CsvSource({"activities,DRAFT", "activities,PUBLISHED", "sessions,DRAFT", "sessions,PUBLISHED"})
    void draftAndPublishedOwnersCanManageMembersWithoutConfusingEmptyAndUnconfigured(String scope, String state)
            throws Exception {
        if ("PUBLISHED".equals(state)) {
            assertThat(activityMapper.updateStatus(activity.getActivityId(), state)).isEqualTo(1);
        }
        assertThat(activityMapper.findById(activity.getActivityId()).getStatus()).isEqualTo(state);
        String path = ownPath(scope);
        long configId = enable(path);
        assertThat(memberCount(configId)).isZero();
        add(path, buyer.getId()).andExpect(status().isCreated()).andExpect(jsonPath("$.memberId").isNumber());
        assertThat(enable(path)).isEqualTo(configId);
        assertThat(memberCount(configId)).isEqualTo(1); // Repeated PUT preserves the list.

        jdbc.update("UPDATE whitelist_config SET updated_at = '2000-01-01 00:00:00' WHERE config_id = ?", configId);
        // A PUBLISHER can also be a participant.
        add(path, otherPublisher.getId()).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT updated_at FROM whitelist_config WHERE config_id = ?",
                LocalDateTime.class, configId)).isAfter(LocalDateTime.of(2000, 1, 1, 0, 0));
        call(HttpMethod.DELETE, path + "/members/" + buyer.getId(), publisher, null).andExpect(status().isNoContent());
        assertThat(memberCount(configId)).isEqualTo(1);
        jdbc.update("UPDATE whitelist_config SET updated_at = '2000-01-01 00:00:00' WHERE config_id = ?", configId);
        call(HttpMethod.DELETE, path + "/members/" + otherPublisher.getId(), publisher, null)
                .andExpect(status().isNoContent());
        assertThat(memberCount(configId)).isZero();
        assertThat(configCount(configId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM whitelist_config WHERE config_id = ?",
                LocalDateTime.class, configId)).isAfter(LocalDateTime.of(2000, 1, 1, 0, 0));
        call(HttpMethod.DELETE, path + "/members/" + otherPublisher.getId(), publisher, null)
                .andExpect(status().isNoContent());
        call(HttpMethod.DELETE, path, publisher, null).andExpect(status().isNoContent());
        assertThat(configCount(configId)).isZero();
        call(HttpMethod.DELETE, path, publisher, null).andExpect(status().isNoContent());
    }

    @ParameterizedTest(name = "{0}, activity={1}, whitelist={2}")
    @CsvSource({
            "activities,CANCELLED,UNCONFIGURED", "activities,CANCELLED,EMPTY", "activities,CANCELLED,MEMBER",
            "sessions,CANCELLED,UNCONFIGURED", "sessions,CANCELLED,EMPTY", "sessions,CANCELLED,MEMBER",
            "activities,FINISHED,UNCONFIGURED", "activities,FINISHED,EMPTY", "activities,FINISHED,MEMBER",
            "sessions,FINISHED,UNCONFIGURED", "sessions,FINISHED,EMPTY", "sessions,FINISHED,MEMBER"
    })
    void terminalActivityStatesRejectAllWhitelistMutationsWithoutChangingData(String scope, String state,
                                                                            String configuration) throws Exception {
        String path = ownPath(scope);
        if (!"UNCONFIGURED".equals(configuration)) {
            enable(path);
            if ("MEMBER".equals(configuration)) {
                add(path, buyer.getId()).andExpect(status().isCreated());
            }
        }
        assertThat(jdbc.update("UPDATE activity SET status = ? WHERE activity_id = ?", state,
                activity.getActivityId())).isEqualTo(1);
        var configsBefore = jdbc.queryForList("SELECT * FROM whitelist_config ORDER BY config_id");
        var membersBefore = jdbc.queryForList("SELECT * FROM whitelist_member ORDER BY member_id");

        rejectAllOperations(path, publisher, 409, "WHITELIST_NOT_EDITABLE");

        assertThat(jdbc.queryForList("SELECT * FROM whitelist_config ORDER BY config_id")).isEqualTo(configsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM whitelist_member ORDER BY member_id")).isEqualTo(membersBefore);
        assertThat(activityMapper.findById(activity.getActivityId()).getStatus()).isEqualTo(state);
    }

    @Test
    void activityAndSessionConfigurationsRemainIndependent() throws Exception {
        String activityPath = ownPath("activities");
        String sessionPath = ownPath("sessions");
        long activityConfig = enable(activityPath);
        add(activityPath, buyer.getId()).andExpect(status().isCreated());
        long sessionConfig = enable(sessionPath);
        assertThat(sessionConfig).isNotEqualTo(activityConfig);
        assertThat(memberCount(sessionConfig)).isZero();
        call(HttpMethod.DELETE, sessionPath, publisher, null).andExpect(status().isNoContent());
        assertThat(configCount(sessionConfig)).isZero();
        assertThat(configCount(activityConfig)).isEqualTo(1);
        assertThat(memberCount(activityConfig)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"activities", "sessions"})
    void anotherPublisherCannotUseAnyManagementOperation(String scope) throws Exception {
        String path = "/publisher/" + scope + "/" +
                (scope.equals("activities") ? otherActivity.getActivityId() : otherSession.getActivitySessionId())
                + "/whitelist";
        rejectAllOperations(path, publisher, 403, "NOT_ACTIVITY_OWNER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM whitelist_config", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"activities", "sessions"})
    void ordinaryUsersCannotUseAnyManagementOperation(String scope) throws Exception {
        rejectAllOperations(ownPath(scope), buyer, 403, "FORBIDDEN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"activities", "sessions"})
    void anonymousRequestsAreUnauthorized(String scope) throws Exception {
        call(HttpMethod.PUT, ownPath(scope), null, null)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"activities", "sessions"})
    void missingResourcesReturnTheirOwnNotFoundCode(String scope) throws Exception {
        call(HttpMethod.PUT, "/publisher/" + scope + "/" + Long.MAX_VALUE + "/whitelist", publisher, null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code")
                        .value(scope.equals("activities") ? "ACTIVITY_NOT_FOUND" : "SESSION_NOT_FOUND"));
    }

    @Test
    void existingSessionWithMissingActivityIsRejected() throws Exception {
        ActivitySession orphan = insertSession(Long.MAX_VALUE);
        call(HttpMethod.PUT, "/publisher/sessions/" + orphan.getActivitySessionId() + "/whitelist", publisher, null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"activities", "sessions"})
    void membersRequireExplicitConfigurationAndExistingAccounts(String scope) throws Exception {
        String path = ownPath(scope);
        add(path, buyer.getId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WHITELIST_NOT_CONFIGURED"));
        call(HttpMethod.DELETE, path + "/members/" + buyer.getId(), publisher, null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WHITELIST_NOT_CONFIGURED"));
        long configId = enable(path);
        add(path, Long.MAX_VALUE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WHITELIST_USER_NOT_FOUND"));
        add(path, buyer.getId()).andExpect(status().isCreated());
        add(path, buyer.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WHITELIST_MEMBER_ALREADY_EXISTS"));
        assertThat(memberCount(configId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"userId\":null}", "{\"userId\":0}", "{\"userId\":-1}",
            "{\"userId\":1,\"configId\":999}", "{"})
    void invalidMemberRequestsReturnStructuredErrors(String body) throws Exception {
        call(HttpMethod.POST, ownPath("activities") + "/members", publisher, body)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_WHITELIST_REQUEST"));
    }

    @Test
    void disabledPublisherCannotUseAnOldToken() throws Exception {
        String token = JwtTestTokens.token(encoder, publisher.getId(), "PUBLISHER");
        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", publisher.getId());
        mvc.perform(request(HttpMethod.PUT, ownPath("activities")).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM whitelist_config", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"activities", "sessions"})
    void failureAfterRealConfigDeletionRollsBackBothConfigAndMemberDeletion(String scope) throws Exception {
        String path = ownPath(scope);
        long configId = enable(path);
        add(path, buyer.getId()).andExpect(status().isCreated());
        failure.armed.set(true);
        assertThatThrownBy(() -> call(HttpMethod.DELETE, path, publisher, null))
                .hasRootCauseMessage("Simulated failure after whitelist config deletion");
        assertThat(configCount(configId)).isEqualTo(1);
        assertThat(memberCount(configId)).isEqualTo(1);
        call(HttpMethod.DELETE, path, publisher, null).andExpect(status().isNoContent());
        assertThat(configCount(configId)).isZero();
        assertThat(memberCount(configId)).isZero();
    }

    @Test
    void qualificationIsPublicWhenBothConfigurationsAreMissing() {
        assertThat(qualified(buyer)).isTrue();
        assertThat(qualified(publisher)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM whitelist_config", Long.class)).isZero();
    }

    @Test
    void qualificationInheritsActivityListAndLaterCallsSeeCommittedManagementChanges() throws Exception {
        String path = ownPath("activities");
        enable(path);
        assertThat(qualified(buyer)).isFalse(); // Explicit empty activity list.
        add(path, buyer.getId()).andExpect(status().isCreated());
        assertThat(qualified(buyer)).isTrue();
        assertThat(qualified(otherPublisher)).isFalse();
        call(HttpMethod.DELETE, path + "/members/" + buyer.getId(), publisher, null).andExpect(status().isNoContent());
        assertThat(qualified(buyer)).isFalse();
        call(HttpMethod.DELETE, path, publisher, null).andExpect(status().isNoContent());
        assertThat(qualified(buyer)).isTrue();
    }

    @Test
    void configuredSessionOverridesActivityWithoutFallingBackOnAnEmptyOrNonmatchingList() throws Exception {
        String activityPath = ownPath("activities");
        String sessionPath = ownPath("sessions");
        enable(activityPath);
        add(activityPath, buyer.getId()).andExpect(status().isCreated());
        enable(sessionPath);
        assertThat(qualified(buyer)).isFalse(); // Activity member, but explicit empty session list.
        add(sessionPath, otherPublisher.getId()).andExpect(status().isCreated());
        assertThat(qualified(buyer)).isFalse(); // Nonempty session list still does not fall back.
        assertThat(qualified(otherPublisher)).isTrue(); // Session member need not be an activity member.
        add(sessionPath, buyer.getId()).andExpect(status().isCreated());
        assertThat(qualified(buyer)).isTrue();
        call(HttpMethod.DELETE, sessionPath + "/members/" + buyer.getId(), publisher, null)
                .andExpect(status().isNoContent());
        assertThat(qualified(buyer)).isFalse();
        call(HttpMethod.DELETE, sessionPath, publisher, null).andExpect(status().isNoContent());
        assertThat(qualified(buyer)).isTrue();
        assertThat(qualified(otherPublisher)).isFalse();
    }

    @Test
    void qualificationRejectsInactiveAccountsAndForeignSessionsEvenForPublicActivities() {
        assertThat(qualificationService.canParticipate(buyer.getId(), activity.getActivityId(),
                otherSession.getActivitySessionId())).isFalse();
        assertThat(qualificationService.canParticipate(Long.MAX_VALUE, activity.getActivityId(),
                session.getActivitySessionId())).isFalse();
        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", buyer.getId());
        assertThat(qualified(buyer)).isFalse();
    }

    private boolean qualified(AppUser user) {
        return qualificationService.canParticipate(user.getId(), activity.getActivityId(), session.getActivitySessionId());
    }

    private void rejectAllOperations(String path, AppUser user, int expectedStatus, String code) throws Exception {
        call(HttpMethod.PUT, path, user, null).andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(code));
        call(HttpMethod.POST, path + "/members", user, "{\"userId\":" + buyer.getId() + "}")
                .andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(code));
        call(HttpMethod.DELETE, path + "/members/" + buyer.getId(), user, null)
                .andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(code));
        call(HttpMethod.DELETE, path, user, null).andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(code));
    }

    private ResultActions call(HttpMethod method, String path, AppUser user, String body) throws Exception {
        var builder = request(method, path);
        if (user != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.token(encoder, user.getId(), user.getRole()));
        }
        if (body != null) { builder.contentType(MediaType.APPLICATION_JSON).content(body); }
        return mvc.perform(builder);
    }

    private ResultActions add(String path, long userId) throws Exception {
        return call(HttpMethod.POST, path + "/members", publisher, "{\"userId\":" + userId + "}");
    }

    private long enable(String path) throws Exception {
        String body = call(HttpMethod.PUT, path, publisher, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.configId").isNumber()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("configId").asLong();
    }

    private String ownPath(String scope) {
        return "/publisher/" + scope + "/" +
                (scope.equals("activities") ? activity.getActivityId() : session.getActivitySessionId()) + "/whitelist";
    }

    private long configCount(long configId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM whitelist_config WHERE config_id = ?", Long.class, configId);
    }

    private long memberCount(long configId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM whitelist_member WHERE config_id = ?", Long.class, configId);
    }

    private AppUser insertUser(String role, String hash) {
        AppUser user = new AppUser();
        user.setUsername("white_" + UUID.randomUUID().toString().replace("-", ""));
        user.setPasswordHash(hash);
        user.setRole(role);
        userMapper.insert(user);
        userIds.add(user.getId());
        return user;
    }

    private Activity insertActivity(Long publisherId) {
        Activity value = new Activity();
        value.setPublisherId(publisherId);
        value.setName("Whitelist integration fixture");
        value.setType("FREE");
        value.setOpenTime(LocalDateTime.of(2099, 1, 1, 0, 0));
        value.setCloseTime(value.getOpenTime().plusDays(1));
        activityMapper.insert(value);
        activityIds.add(value.getActivityId());
        return value;
    }

    private ActivitySession insertSession(Long activityId) {
        ActivitySession value = new ActivitySession();
        value.setActivityId(activityId);
        value.setStartTime(LocalDateTime.of(2099, 1, 3, 0, 0));
        value.setEndTime(value.getStartTime().plusHours(2));
        value.setCapacity(10);
        sessionMapper.insert(value);
        sessionIds.add(value.getActivitySessionId());
        return value;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        SingleConnectionDataSource dataSource(DataSourceProperties properties) {
            return new SingleConnectionDataSource(properties.determineUrl(), properties.determineUsername(),
                    properties.determinePassword(), true);
        }

        @Bean
        DeleteFailure deleteFailure() { return new DeleteFailure(); }
    }

    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static class DeleteFailure implements Interceptor {
        private final AtomicBoolean armed = new AtomicBoolean();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().equals("com.limitactivitysystem.whitelist.mapper.WhitelistConfigMapper.deleteById")
                    && armed.getAndSet(false)) {
                throw new IllegalStateException("Simulated failure after whitelist config deletion");
            }
            return result;
        }
    }
}
