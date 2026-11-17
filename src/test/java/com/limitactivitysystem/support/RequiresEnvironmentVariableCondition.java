package com.limitactivitysystem.support;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.AnnotatedElement;
import java.util.function.Function;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Keeps environment opt-ins effective when an IDE disables JUnit's built-in Enabled conditions. */
public final class RequiresEnvironmentVariableCondition
        implements ExecutionCondition, BeforeAllCallback, BeforeEachCallback {
    private final Function<String, String> environment;

    public RequiresEnvironmentVariableCondition() {
        this(System::getenv);
    }

    RequiresEnvironmentVariableCondition(Function<String, String> environment) {
        this.environment = environment;
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return evaluate(context, true);
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        require(evaluate(context, false));
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        // Lifecycle callbacks still guard execution if all ExecutionConditions are explicitly disabled.
        require(evaluate(context, true));
    }

    private ConditionEvaluationResult evaluate(ExtensionContext context, boolean includeMethod) {
        var classResult = evaluate(context.getTestClass().orElse(null));
        if (classResult.isDisabled() || !includeMethod) {
            return classResult;
        }
        return evaluate(context.getTestMethod().orElse(null));
    }

    private ConditionEvaluationResult evaluate(AnnotatedElement element) {
        var requirement = element == null ? null : element.getAnnotation(RequiresEnvironmentVariable.class);
        if (requirement == null) {
            return ConditionEvaluationResult.enabled("No environment requirement on this element");
        }
        String value = environment.apply(requirement.named());
        if (value != null && value.matches(requirement.matches())) {
            return ConditionEvaluationResult.enabled("Environment requirement satisfied: " + requirement.named());
        }
        // Never expose the supplied value, which may be a password.
        String state = value == null ? "is not set"
                : value.isEmpty() ? "is empty" : "is set but does not match the required pattern";
        return ConditionEvaluationResult.disabled("Scenario not executed: environment variable "
                + requirement.named() + " " + state + "; required pattern: " + requirement.matches()
                + ". Check this test's IDEA Run/Debug Configuration > Environment variables."
                + " VM options (-D) do not set environment variables.");
    }

    private static void require(ConditionEvaluationResult result) {
        assumeFalse(result.isDisabled(), () -> result.getReason().orElse("Environment requirement not satisfied"));
    }
}
