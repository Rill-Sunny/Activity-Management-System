package com.limitactivitysystem.whitelist.service;

import com.limitactivitysystem.activity.mapper.ActivityMapper;
import com.limitactivitysystem.activity.mapper.ActivitySessionMapper;
import com.limitactivitysystem.activity.model.Activity;
import com.limitactivitysystem.activity.model.ActivitySession;
import com.limitactivitysystem.identity.mapper.AppUserMapper;
import com.limitactivitysystem.identity.model.AppUser;
import com.limitactivitysystem.security.CurrentUserProvider;
import com.limitactivitysystem.whitelist.exception.QualificationDeniedException;
import com.limitactivitysystem.whitelist.mapper.WhitelistConfigMapper;
import com.limitactivitysystem.whitelist.mapper.WhitelistMemberMapper;
import com.limitactivitysystem.whitelist.model.WhitelistConfig;
import com.limitactivitysystem.whitelist.model.WhitelistScopeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.limitactivitysystem.whitelist.model.WhitelistScopeType.ACTIVITY;
import static com.limitactivitysystem.whitelist.model.WhitelistScopeType.SESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class QualificationServiceTest {
    @Mock private AppUserMapper users;
    @Mock private ActivityMapper activities;
    @Mock private ActivitySessionMapper sessions;
    @Mock private WhitelistConfigMapper configs;
    @Mock private WhitelistMemberMapper members;
    @Mock private CurrentUserProvider currentUserProvider;
    @InjectMocks private QualificationService service;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void businessEntryUsesTheCurrentUserAndReturnsTheTrustedId(boolean recheck) {
        validContext();
        given(currentUserProvider.getCurrentUserId()).willReturn(1L);
        Long userId = recheck ? service.checkAgain(10L, 20L) : service.check(10L, 20L);
        assertThat(userId).isEqualTo(1L);
        verify(users).findByIdForShare(1L);
        verify(currentUserProvider).getCurrentUserId();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void businessEntryThrowsWhenTheCurrentUserIsNotInTheConfiguredList(boolean recheck) {
        validContext();
        given(currentUserProvider.getCurrentUserId()).willReturn(1L);
        given(configs.findByScopeForShare(SESSION, 20L)).willReturn(config(200L));
        given(members.findIdByConfigIdAndUserIdForShare(200L, 1L)).willReturn(null);
        assertThatThrownBy(() -> {
            if (recheck) {
                service.checkAgain(10L, 20L);
            } else {
                service.check(10L, 20L);
            }
        }).isInstanceOf(QualificationDeniedException.class);
        verify(configs, never()).findByScopeForShare(ACTIVITY, 10L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "PUBLISHER"})
    void unconfiguredScopesArePublicForEitherActiveRole(String role) {
        AppUser user = validContext();
        user.setRole(role);
        assertThat(service.canParticipate(1L, 10L, 20L)).isTrue();
        var order = inOrder(configs);
        order.verify(configs).findByScopeForShare(SESSION, 20L);
        order.verify(configs).findByScopeForShare(ACTIVITY, 10L);
        verifyNoInteractions(members);
        verify(activities, never()).findByIdForUpdate(anyLong());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("qualificationScenarios")
    void appliesTheSixQualificationRules(String scenario, Set<Long> activityMembers,
                                         Set<Long> sessionMembers, boolean expected) {
        validContext();
        // null means unconfigured; an empty set means an existing config with no members.
        Map<WhitelistScopeType, WhitelistConfig> configuredScopes = new HashMap<>();
        Map<Long, Set<Long>> membersByConfig = new HashMap<>();
        if (activityMembers != null) {
            configuredScopes.put(ACTIVITY, config(100L));
            membersByConfig.put(100L, activityMembers);
        }
        if (sessionMembers != null) {
            configuredScopes.put(SESSION, config(200L));
            membersByConfig.put(200L, sessionMembers);
        }
        given(configs.findByScopeForShare(any(WhitelistScopeType.class), anyLong())).willAnswer(invocation -> {
            WhitelistScopeType scope = invocation.getArgument(0);
            Long scopeId = invocation.getArgument(1);
            assertThat(scopeId).isEqualTo(scope == SESSION ? 20L : 10L);
            return configuredScopes.get(scope);
        });
        if (!configuredScopes.isEmpty()) {
            given(members.findIdByConfigIdAndUserIdForShare(anyLong(), anyLong())).willAnswer(invocation -> {
                Long configId = invocation.getArgument(0);
                Long userId = invocation.getArgument(1);
                return membersByConfig.get(configId).contains(userId) ? 300L : null;
            });
        }

        assertThat(service.canParticipate(1L, 10L, 20L)).as(scenario).isEqualTo(expected);
        if (sessionMembers != null) {
            verify(configs, never()).findByScopeForShare(ACTIVITY, 10L);
            verify(members).findIdByConfigIdAndUserIdForShare(200L, 1L);
        } else {
            var order = inOrder(configs);
            order.verify(configs).findByScopeForShare(SESSION, 20L);
            order.verify(configs).findByScopeForShare(ACTIVITY, 10L);
            if (activityMembers != null) {
                verify(members).findIdByConfigIdAndUserIdForShare(100L, 1L);
            } else {
                verifyNoInteractions(members);
            }
        }
    }

    static Stream<Arguments> qualificationScenarios() {
        return Stream.of(
                Arguments.of("两级均未配置，合法用户通过", null, null, true),
                Arguments.of("活动名单包含用户，场次未配置，通过", Set.of(1L), null, true),
                Arguments.of("活动名单不包含用户，场次未配置，拒绝", Set.of(2L), null, false),
                Arguments.of("用户只在活动名单，场次名单不含用户，拒绝", Set.of(1L), Set.of(2L), false),
                Arguments.of("用户只在场次名单，活动名单不含用户，通过", Set.of(2L), Set.of(1L), true),
                Arguments.of("场次显式空名单，即使活动名单包含用户也拒绝", Set.of(1L), Set.<Long>of(), false));
    }

    @Test
    void missingAccountCannotParticipateInPublicScopes() {
        assertThat(service.canParticipate(1L, 10L, 20L)).isFalse();
        verifyNoInteractions(activities, sessions, configs, members);
    }

    @Test
    void disabledAccountCannotParticipateInPublicScopes() {
        AppUser user = new AppUser();
        user.setStatus("DISABLED");
        given(users.findByIdForShare(1L)).willReturn(user);
        assertThat(service.canParticipate(1L, 10L, 20L)).isFalse();
        verifyNoInteractions(activities, sessions, configs, members);
    }

    @Test
    void missingActivityCannotBeTreatedAsPublic() {
        given(users.findByIdForShare(1L)).willReturn(new AppUser());
        assertThat(service.canParticipate(1L, 10L, 20L)).isFalse();
        verifyNoInteractions(sessions, configs, members);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingSessionOrForeignSessionCannotBypassActivityRules(boolean missing) {
        given(users.findByIdForShare(1L)).willReturn(new AppUser());
        given(activities.findByIdForShare(10L)).willReturn(new Activity());
        if (!missing) {
            ActivitySession foreign = new ActivitySession();
            foreign.setActivityId(99L);
            given(sessions.findByIdForShare(20L)).willReturn(foreign);
        }
        assertThat(service.canParticipate(1L, 10L, 20L)).isFalse();
        verifyNoInteractions(configs, members);
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    void invalidIdentifiersDenyBeforeAnyQuery(Long userId, Long activityId, Long sessionId) {
        assertThat(service.canParticipate(userId, activityId, sessionId)).isFalse();
        verifyNoInteractions(users, activities, sessions, configs, members);
    }

    static Stream<Arguments> invalidIds() {
        return Stream.of(Arguments.of(null, 10L, 20L), Arguments.of(0L, 10L, 20L), Arguments.of(-1L, 10L, 20L),
                Arguments.of(1L, null, 20L), Arguments.of(1L, 0L, 20L), Arguments.of(1L, -1L, 20L),
                Arguments.of(1L, 10L, null), Arguments.of(1L, 10L, 0L), Arguments.of(1L, 10L, -1L));
    }

    private AppUser validContext() {
        AppUser user = new AppUser();
        user.setId(1L);
        given(users.findByIdForShare(1L)).willReturn(user);
        Activity activity = new Activity();
        activity.setActivityId(10L);
        given(activities.findByIdForShare(10L)).willReturn(activity);
        ActivitySession session = new ActivitySession();
        session.setActivityId(10L);
        given(sessions.findByIdForShare(20L)).willReturn(session);
        return user;
    }

    private WhitelistConfig config(Long id) {
        WhitelistConfig config = new WhitelistConfig();
        config.setConfigId(id);
        return config;
    }
}
