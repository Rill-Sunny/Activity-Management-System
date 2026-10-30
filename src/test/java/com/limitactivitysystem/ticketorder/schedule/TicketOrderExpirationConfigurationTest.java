package com.limitactivitysystem.ticketorder.schedule;

import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.service.TicketOrderCloseService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TicketOrderExpirationConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(TicketOrderExpirationConfiguration.class)
            .withBean(TicketOrderMapper.class, () -> mock(TicketOrderMapper.class))
            .withBean(TicketOrderCloseService.class, () -> mock(TicketOrderCloseService.class))
            .withPropertyValues("ticket-order.expiration.initial-delay-ms=60000");

    @Test void schedulingIsEnabledByDefaultAndRegistersTheExpirationTrigger() {
        context.run(application -> {
            assertThat(application).hasSingleBean(TicketOrderExpirationJob.class);
            assertThat(application.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
        });
    }

    @Test void disablingExpirationRemovesTheBackgroundTrigger() {
        context.withPropertyValues("ticket-order.expiration.enabled=false").run(application -> {
            assertThat(application).doesNotHaveBean(TicketOrderExpirationJob.class);
            assertThat(application).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }
}
