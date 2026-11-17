package com.limitactivitysystem.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.TestAbortedException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequiresEnvironmentVariableConditionTest {
    @Test
    void missingClassRequirementStopsClassSetupEvenIfConditionsAreBypassed() throws Exception {
        var condition = new RequiresEnvironmentVariableCondition(Map.<String, String>of()::get);
        var context = context("ordinaryScenario");

        assertThat(condition.evaluateExecutionCondition(context).isDisabled()).isTrue();
        assertThatThrownBy(() -> condition.beforeAll(context))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("DB_PASSWORD is not set")
                .hasMessageContaining("Environment variables");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"false", "TRUE", " true "})
    void faultRequiresExplicitOptInEvenIfConditionsAreBypassed(String flag) throws Exception {
        var environment = new HashMap<>(Map.of("DB_PASSWORD", "test-secret"));
        environment.put("RUN_DAY21_FAULT_TESTS", flag);
        var condition = new RequiresEnvironmentVariableCondition(environment::get);
        var context = context("faultScenario");

        assertThat(condition.evaluateExecutionCondition(context).isDisabled()).isTrue();
        assertThatCode(() -> condition.beforeAll(context)).doesNotThrowAnyException();
        assertThatThrownBy(() -> condition.beforeEach(context))
                .isInstanceOf(TestAbortedException.class).hasMessageContaining("RUN_DAY21_FAULT_TESTS")
                .hasMessageContaining(flag == null ? "is not set"
                        : flag.isEmpty() ? "is empty" : "is set but does not match")
                .hasMessageNotContaining("test-secret");
    }

    @Test
    void methodOptInDoesNotOverrideTheClassRequirement() throws Exception {
        var condition = new RequiresEnvironmentVariableCondition(Map.of("RUN_DAY21_FAULT_TESTS", "true")::get);
        var context = context("faultScenario");

        assertThat(condition.evaluateExecutionCondition(context).isDisabled()).isTrue();
        assertThatThrownBy(() -> condition.beforeEach(context))
                .isInstanceOf(TestAbortedException.class).hasMessageContaining("DB_PASSWORD");
    }

    @Test
    void ordinaryScenarioDoesNotNeedFaultOptIn() throws Exception {
        var condition = new RequiresEnvironmentVariableCondition(Map.of("DB_PASSWORD", "test-secret")::get);
        var context = context("ordinaryScenario");

        assertThat(condition.evaluateExecutionCondition(context).isDisabled()).isFalse();
        assertThatCode(() -> condition.beforeAll(context)).doesNotThrowAnyException();
        assertThatCode(() -> condition.beforeEach(context)).doesNotThrowAnyException();
    }

    @Test
    void bothRequirementsAllowTheFaultScenarioToRun() throws Exception {
        var condition = new RequiresEnvironmentVariableCondition(
                Map.of("DB_PASSWORD", "test-secret", "RUN_DAY21_FAULT_TESTS", "true")::get);
        var context = context("faultScenario");

        assertThat(condition.evaluateExecutionCondition(context).isDisabled()).isFalse();
        assertThatCode(() -> condition.beforeAll(context)).doesNotThrowAnyException();
        assertThatCode(() -> condition.beforeEach(context)).doesNotThrowAnyException();
    }

    private static ExtensionContext context(String method) throws Exception {
        var context = mock(ExtensionContext.class);
        when(context.getTestClass()).thenReturn(Optional.of(ScenarioFixture.class));
        when(context.getTestMethod()).thenReturn(Optional.of(ScenarioFixture.class.getDeclaredMethod(method)));
        return context;
    }

    @RequiresEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
    private static class ScenarioFixture {
        void ordinaryScenario() { }

        @RequiresEnvironmentVariable(named = "RUN_DAY21_FAULT_TESTS", matches = "true")
        void faultScenario() { }
    }
}
