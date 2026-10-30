package com.limitactivitysystem.ticketorder.schedule;

import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.service.TicketOrderCloseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "ticket-order.expiration", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TicketOrderExpirationConfiguration {
    @Bean
    TicketOrderExpirationJob ticketOrderExpirationJob(TicketOrderMapper orders, TicketOrderCloseService closing,
            @Value("${ticket-order.expiration.batch-size:100}") int batchSize) {
        return new TicketOrderExpirationJob(orders, closing, batchSize);
    }
}
