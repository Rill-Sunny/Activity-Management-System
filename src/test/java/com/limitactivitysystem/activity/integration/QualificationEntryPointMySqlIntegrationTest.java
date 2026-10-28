package com.limitactivitysystem.activity.integration;

import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.whitelist.model.WhitelistScopeType;
import com.limitactivitysystem.whitelist.service.PublisherWhitelistService;
import com.limitactivitysystem.whitelist.service.QualificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.limitactivitysystem.whitelist.model.WhitelistScopeType.ACTIVITY;
import static com.limitactivitysystem.whitelist.model.WhitelistScopeType.SESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The test-only consumer exercises the same transaction boundary as future business Services.
@SpringBootTest(properties = {JwtTestTokens.SECRET_PROPERTY, "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import({QualificationEntryPointMySqlIntegrationTest.DatabaseConfiguration.class,
        QualificationEntryPointMySqlIntegrationTest.ConsumerService.class,
        QualificationEntryPointMySqlIntegrationTest.ProbeController.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class QualificationEntryPointMySqlIntegrationTest extends ActivityMySqlTestSupport {

    @Autowired private QualificationService qualification;
    @Autowired private PublisherWhitelistService management;
    private Activity activity;
    private ActivitySession session;

    @BeforeEach
    void createScope() {
        activity = insertDraft(publisher, "FREE");
        session = insertSession(activity);
    }

    @AfterEach
    void removeWhitelistFixturesFromTheOwnedSchema() {
        jdbc.update("DELETE FROM whitelist_member");
        jdbc.update("DELETE FROM whitelist_config");
    }

    @ParameterizedTest
    @CsvSource({"CHECK,false", "CHECK,true", "CHECK_AGAIN,false", "CHECK_AGAIN,true"})
    void returnsTheTrustedIdForEitherActiveConsumerRole(Entry entry, boolean publisherRole) throws Exception {
        AppUser actor = publisherRole ? publisher : buyer;
        invoke(entry, actor).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(actor.getId()));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("qualificationRules")
    void bothBusinessEntriesUseTheSixQualificationRules(Entry entry, Rule rule) throws Exception {
        switch (rule) {
            case PUBLIC -> { }
            case ACTIVITY_MEMBER -> configure(ACTIVITY, buyer);
            case ACTIVITY_NONMEMBER -> configure(ACTIVITY, otherPublisher);
            case SESSION_NONMEMBER_OVERRIDES -> {
                configure(ACTIVITY, buyer);
                configure(SESSION, otherPublisher);
            }
            case SESSION_MEMBER_OVERRIDES -> {
                configure(ACTIVITY, otherPublisher);
                configure(SESSION, buyer);
            }
            case EMPTY_SESSION -> {
                configure(ACTIVITY, buyer);
                configure(SESSION, null);
            }
        }
        ResultActions response = invoke(entry, buyer);
        if (rule == Rule.PUBLIC || rule == Rule.ACTIVITY_MEMBER || rule == Rule.SESSION_MEMBER_OVERRIDES) {
            response.andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(buyer.getId()));
        } else {
            denied(response);
        }
    }

    static Stream<Arguments> qualificationRules() {
        return Arrays.stream(Entry.values()).flatMap(entry -> Arrays.stream(Rule.values())
                .map(rule -> Arguments.of(entry, rule)));
    }

    @ParameterizedTest(name = "AT-04: {0}")
    @EnumSource(Entry.class)
    void emptySessionListRejectsAllConsumerRolesAndRemovingItRestoresInheritance(Entry entry) throws Exception {
        List<AppUser> actors = List.of(buyer, publisher, otherPublisher);
        configure(ACTIVITY, buyer);
        asPublisher(() -> management.addMember(ACTIVITY, activity.getActivityId(), publisher.getId()));
        asPublisher(() -> management.addMember(ACTIVITY, activity.getActivityId(), otherPublisher.getId()));
        configure(SESSION, null);

        // Even the owner and other publishers cannot bypass an explicitly empty session list.
        for (AppUser actor : actors) {
            denied(invoke(entry, actor));
        }

        asPublisher(() -> {
            management.disable(SESSION, session.getActivitySessionId());
            return null;
        });
        for (AppUser actor : actors) {
            invoke(entry, actor).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(actor.getId()));
        }
        asPublisher(() -> {
            management.removeMember(ACTIVITY, activity.getActivityId(), buyer.getId());
            return null;
        });
        denied(invoke(entry, buyer)); // An unconfigured session still enforces its activity list.

        asPublisher(() -> {
            management.disable(ACTIVITY, activity.getActivityId());
            return null;
        });
        for (AppUser actor : actors) {
            invoke(entry, actor).andExpect(status().isOk()); // Both scopes are now unconfigured.
        }

        configure(SESSION, null);
        for (AppUser actor : actors) {
            denied(invoke(entry, actor)); // Empty also denies when the activity has no configuration.
        }
    }

    @Test
    void paymentRechecksCommittedRemovalAndRestorationInsteadOfCachingTheOrderCreationResult() throws Exception {
        configure(ACTIVITY, buyer);
        invoke(Entry.CHECK, buyer).andExpect(status().isOk());
        asPublisher(() -> {
            management.removeMember(ACTIVITY, activity.getActivityId(), buyer.getId());
            return null;
        });
        denied(invoke(Entry.CHECK_AGAIN, buyer));
        asPublisher(() -> management.addMember(ACTIVITY, activity.getActivityId(), buyer.getId()));
        invoke(Entry.CHECK_AGAIN, buyer).andExpect(status().isOk());
    }

    @Test
    void paymentSeesANewEmptySessionOverrideAndLaterConfigurationRemoval() throws Exception {
        configure(ACTIVITY, buyer);
        invoke(Entry.CHECK, buyer).andExpect(status().isOk());
        configure(SESSION, null);
        denied(invoke(Entry.CHECK_AGAIN, buyer));
        asPublisher(() -> {
            management.disable(SESSION, session.getActivitySessionId());
            return null;
        });
        invoke(Entry.CHECK_AGAIN, buyer).andExpect(status().isOk());
    }

    @Test
    void paymentCannotReuseAnOldTokenAfterTheAccountWasDisabled() throws Exception {
        String token = bearer(buyer);
        mvc.perform(post(path(Entry.CHECK)).header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());
        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", buyer.getId());
        mvc.perform(post(path(Entry.CHECK_AGAIN)).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void bothEntriesRequireAnExistingBusinessTransaction(Entry entry) {
        assertThatThrownBy(() -> asPublisher(() -> entry == Entry.CHECK
                ? qualification.check(activity.getActivityId(), session.getActivitySessionId())
                : qualification.checkAgain(activity.getActivityId(), session.getActivitySessionId())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void existingButMismatchedScopeIdsDoNotQualify(Entry entry) throws Exception {
        ActivitySession otherSession = insertSession(insertDraft(publisher, "FREE"));
        denied(mvc.perform(post("/test/qualification/{entry}/{activityId}/{sessionId}", entry,
                        activity.getActivityId(), otherSession.getActivitySessionId())
                .header(HttpHeaders.AUTHORIZATION, bearer(buyer))));
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void anonymousRequestsCannotReachTheConsumer(Entry entry) throws Exception {
        mvc.perform(post(path(entry))).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void aSuppliedUserIdCannotReplaceTheTokenUser(Entry entry) throws Exception {
        configure(ACTIVITY, otherPublisher);
        denied(mvc.perform(post(path(entry)).param("userId", otherPublisher.getId().toString())
                .header(HttpHeaders.AUTHORIZATION, bearer(buyer))));
    }

    private void configure(WhitelistScopeType scope, AppUser member) throws Exception {
        Long scopeId = scope == ACTIVITY ? activity.getActivityId() : session.getActivitySessionId();
        asPublisher(() -> management.enable(scope, scopeId));
        if (member != null) {
            asPublisher(() -> management.addMember(scope, scopeId, member.getId()));
        }
    }

    private String path(Entry entry) {
        return "/test/qualification/" + entry + "/" + activity.getActivityId() + "/" + session.getActivitySessionId();
    }

    private ResultActions invoke(Entry entry, AppUser actor) throws Exception {
        return mvc.perform(post(path(entry)).header(HttpHeaders.AUTHORIZATION, bearer(actor)));
    }

    private void denied(ResultActions response) throws Exception {
        response.andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403)).andExpect(jsonPath("$.code").value("QUALIFICATION_DENIED"));
    }

    enum Entry { CHECK, CHECK_AGAIN }
    enum Rule { PUBLIC, ACTIVITY_MEMBER, ACTIVITY_NONMEMBER, SESSION_NONMEMBER_OVERRIDES, SESSION_MEMBER_OVERRIDES, EMPTY_SESSION }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    static class ConsumerService {
        private final QualificationService qualification;

        ConsumerService(QualificationService qualification) { this.qualification = qualification; }

        public Long act(Entry entry, Long activityId, Long sessionId) {
            return entry == Entry.CHECK ? qualification.check(activityId, sessionId)
                    : qualification.checkAgain(activityId, sessionId);
        }
    }

    @TestComponent
    @RestController
    static class ProbeController {
        private final ConsumerService consumer;

        ProbeController(ConsumerService consumer) { this.consumer = consumer; }

        @PostMapping("/test/qualification/{entry}/{activityId}/{sessionId}")
        public Map<String, Long> act(@PathVariable Entry entry, @PathVariable Long activityId,
                                     @PathVariable Long sessionId) {
            return Map.of("userId", consumer.act(entry, activityId, sessionId));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        IsolatedQualificationMySqlDataSource dataSource(DataSourceProperties properties) throws Exception {
            return new IsolatedQualificationMySqlDataSource(properties);
        }
    }
}
