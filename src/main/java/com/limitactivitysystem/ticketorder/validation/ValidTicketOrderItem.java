package com.limitactivitysystem.ticketorder.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target(TYPE)
@Retention(RUNTIME)
@Constraint(validatedBy = TicketOrderItemValidator.class)
public @interface ValidTicketOrderItem {
    String message() default "Order item fields do not match the allocation mode";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
