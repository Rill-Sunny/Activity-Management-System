package com.limitactivitysystem.observability;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

import java.lang.reflect.Method;
import java.util.Map;

/** Uses Spring's existing transaction proxy infrastructure; no AspectJ dependency or business constructor changes. */
@Configuration(proxyBeanMethods = false)
public class BusinessObservationConfiguration {
    private static final Map<String, Map<String, String>> OPERATIONS = Map.of(
        "com.limitactivitysystem.ticketorder.service.TicketPaymentService", Map.of("pay", "ticket.payment"),
        "com.limitactivitysystem.product.service.ProductPaymentService", Map.of("pay", "product.payment"),
        "com.limitactivitysystem.refund.service.TicketRefundProcessingService", Map.of("process", "ticket.refund", "processForCancellation", "ticket.refund"),
        "com.limitactivitysystem.product.service.ProductAfterSaleService", Map.of("apply", "product.aftersale", "applyForQualification", "product.aftersale"),
        "com.limitactivitysystem.qualification.service.QualificationAfterSaleService", Map.of("processTask", "qualification.task.process"),
        "com.limitactivitysystem.cancellation.CancellationItemProcessor", Map.of("process", "cancellation.item.process"),
        "com.limitactivitysystem.messaging.TicketQualificationChangedConsumer", Map.of("consume", "mq.consume"),
        "com.limitactivitysystem.messaging.AsyncEventConsumer", Map.of(
            "consumeCancellation", "mq.consume", "consumeRescheduleNotice", "mq.consume", "consumeRescheduleRetention", "mq.consume",
            "consumeOrderExpiry", "mq.consume", "consumePaymentNotification", "mq.consume", "consumeRefundNotification", "mq.consume")
    );

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static DefaultPointcutAdvisor businessObservationAdvisor(ObjectProvider<BusinessTelemetry> telemetry) {
        var pointcut = new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> targetClass) {
                return operation(method, targetClass) != null;
            }
        };
        var advisor = new DefaultPointcutAdvisor(pointcut, (MethodInterceptor) invocation ->
                telemetry.getObject().invoke(operation(invocation.getMethod(), invocation.getThis().getClass()), invocation));
        // Surround transaction advice: commit failures count as failures, and the span ends after commit/rollback.
        advisor.setOrder(-100);
        return advisor;
    }

    static String operation(Method method, Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            var methods = OPERATIONS.get(current.getName());
            if (methods != null) { return methods.get(method.getName()); }
        }
        return null;
    }
}
