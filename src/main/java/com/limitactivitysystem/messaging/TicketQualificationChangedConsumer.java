package com.limitactivitysystem.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.qualification.model.TicketQualificationChangedEvent;
import com.limitactivitysystem.qualification.service.QualificationAfterSaleService;
import com.limitactivitysystem.qualification.service.QualificationAfterSaleTaskFailureService;
import com.limitactivitysystem.qualification.service.QualificationTelemetry;
import com.limitactivitysystem.qualification.service.TicketQualificationChangedHandlerService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class TicketQualificationChangedConsumer {
    private static final Logger log = LoggerFactory.getLogger(TicketQualificationChangedConsumer.class);

    private final TicketQualificationChangedHandlerService handlers;
    private final QualificationAfterSaleService afterSales;
    private final QualificationAfterSaleTaskFailureService taskFailures;
    private final ConsumerFailureService failures;
    private final ConfirmedRetryPublisher retryPublisher;
    private final ObjectMapper objectMapper;
    private final QualificationTelemetry telemetry;
    private final int maxAttempts;

    public TicketQualificationChangedConsumer(TicketQualificationChangedHandlerService handlers,
            QualificationAfterSaleService afterSales, QualificationAfterSaleTaskFailureService taskFailures,
            ConsumerFailureService failures, ConfirmedRetryPublisher retryPublisher, ObjectMapper objectMapper,
            QualificationTelemetry telemetry,
            @Value("${messaging.consumer.max-attempts:3}") int maxAttempts) {
        this.handlers = handlers;
        this.afterSales = afterSales;
        this.taskFailures = taskFailures;
        this.failures = failures;
        this.retryPublisher = retryPublisher;
        this.objectMapper = objectMapper;
        this.telemetry = telemetry;
        this.maxAttempts = maxAttempts;
    }

    @RabbitListener(queues = "${messaging.rabbit.name-prefix:}" + RabbitMqTopology.TICKET_QUALIFICATION_CHANGED_QUEUE)
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String eventId = eventId(message);
        try {
            if (eventId == null) { throw new IllegalArgumentException("Rabbit message has no valid x-event-id"); }
            TicketQualificationChangedEvent event = objectMapper.readValue(
                    message.getBody(), TicketQualificationChangedEvent.class);
            if (!eventId.equals(event.eventId())) {
                throw new IllegalArgumentException("Rabbit event ID differs from the payload event ID");
            }
            telemetry.eventReceived(eventId);
            for (Long taskId : handlers.registerTasks(event)) {
                try {
                    afterSales.processTask(taskId);
                } catch (RuntimeException taskFailure) {
                    try { taskFailures.record(taskId, taskFailure); }
                    catch (RuntimeException ledgerFailure) { taskFailure.addSuppressed(ledgerFailure); }
                    throw taskFailure;
                }
            }
            // Task registration and task effects commit before the listener sends this ACK.
            channel.basicAck(deliveryTag, false);
        } catch (Exception failure) {
            int nextAttempt = retryCount(message) + 1;
            boolean retrySent = eventId != null && nextAttempt < maxAttempts
                    && retryPublisher.publish(message, "ticket.qualification.changed.retry", nextAttempt);
            com.limitactivitysystem.observability.BusinessTelemetry.consumerFailure(retrySent, failure);
            if (eventId != null) {
                try {
                    failures.record(TicketQualificationChangedHandlerService.CONSUMER_NAME,
                            eventId, nextAttempt, !retrySent, compactReason(failure));
                } catch (RuntimeException ledgerFailure) {
                    failure.addSuppressed(ledgerFailure);
                    log.error("Could not record qualification consumer failure: eventId={} errorType={}", eventId, ledgerFailure.getClass().getSimpleName());
                }
            }
            if (retrySent) {
                log.warn("Qualification event failed; retry scheduled: eventId={} attempt={}",
                        eventId, nextAttempt);
                channel.basicAck(deliveryTag, false);
            } else {
                log.error("Qualification event sent to dead-letter queue: eventId={} attempt={}",
                        eventId, nextAttempt);
                channel.basicReject(deliveryTag, false);
            }
        }
    }

    private String eventId(Message message) {
        Object value = message.getMessageProperties().getHeader("x-event-id");
        if (value == null) { value = message.getMessageProperties().getMessageId(); }
        String id = value == null ? null : value.toString();
        return id != null && id.length() == 36 ? id : null;
    }

    private int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeader("x-las-retry-count");
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private String compactReason(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) { root = root.getCause(); }
        String text = root.getClass().getSimpleName();
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }
}
