package com.limitactivitysystem.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class ConfirmedRetryPublisher {
    private static final Logger log = LoggerFactory.getLogger(ConfirmedRetryPublisher.class);
    private final RabbitTemplate rabbitTemplate;
    private final String eventsExchange;

    public ConfirmedRetryPublisher(RabbitTemplate rabbitTemplate,
            @Value("${messaging.rabbit.name-prefix:}") String namePrefix) {
        this.rabbitTemplate = rabbitTemplate;
        this.eventsExchange = namePrefix + RabbitMqTopology.EVENTS_EXCHANGE;
    }

    public boolean publish(Message original, String routingKey, int retryAttempt) {
        try {
            Message retry = MessageBuilder.fromMessage(original)
                    .setHeader("x-las-retry-count", retryAttempt)
                    .build();
            retry.getMessageProperties().setExpiration(null);
            retry.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
            rabbitTemplate.send(eventsExchange, routingKey, retry, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                log.error("Retry message was not routed/confirmed: eventId={} routingKey={} cause={}",
                        retry.getMessageProperties().getHeader("x-event-id"), routingKey, confirm.getReason());
                return false;
            }
            return true;
        } catch (Exception exception) {
            log.error("Could not publish a bounded retry: routingKey={} errorType={}", routingKey, exception.getClass().getSimpleName());
            return false;
        }
    }
}
