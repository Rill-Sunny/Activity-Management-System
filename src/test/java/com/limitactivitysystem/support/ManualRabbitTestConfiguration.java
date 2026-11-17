package com.limitactivitysystem.support;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Supplies an admin without connecting to RabbitMQ during MySQL-only test setup. */
@TestConfiguration(proxyBeanMethods = false)
public class ManualRabbitTestConfiguration {
    @Bean
    RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(false);
        return admin;
    }
}
