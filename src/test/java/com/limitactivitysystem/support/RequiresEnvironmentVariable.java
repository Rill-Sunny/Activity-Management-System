package com.limitactivitysystem.support;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An explicit environment requirement that also applies to tests launched directly from IDEA. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ExtendWith(RequiresEnvironmentVariableCondition.class)
public @interface RequiresEnvironmentVariable {
    String named();
    String matches();
}
