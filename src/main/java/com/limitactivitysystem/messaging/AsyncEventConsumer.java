package com.limitactivitysystem.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class AsyncEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(AsyncEventConsumer.class);
    private final AsyncEventHandlerService handlers;
    private final ConsumerFailureService failures;
    private final ConfirmedRetryPublisher retryPublisher;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;
    private final com.limitactivitysystem.reschedule.RescheduleAsyncHandler reschedules;
    private final com.limitactivitysystem.cancellation.CancellationBatchService cancellations;

    public AsyncEventConsumer(AsyncEventHandlerService handlers, ConsumerFailureService failures,
            ConfirmedRetryPublisher retryPublisher, ObjectMapper objectMapper,
            @Value("${messaging.consumer.max-attempts:3}") int maxAttempts,
            com.limitactivitysystem.reschedule.RescheduleAsyncHandler reschedules,
            com.limitactivitysystem.cancellation.CancellationBatchService cancellations) {
        this.handlers = handlers;
        this.failures = failures;
        this.retryPublisher = retryPublisher;
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
        this.reschedules = reschedules;
        this.cancellations = cancellations;
    }

    @RabbitListener(queues="${messaging.rabbit.name-prefix:}"+RabbitMqTopology.CANCELLATION_QUEUE)
    public void consumeCancellation(Message message,Channel channel) throws IOException {
        consume(message,channel,com.limitactivitysystem.cancellation.CancellationBatchService.CONSUMER,
                "cancellation.execute.retry",CancellationPayload.class,(id,payload)->{
                    if(!id.equals(payload.eventId())||payload.cancellationEventId()==null||payload.cancellationEventId()<=0)
                        throw new IllegalArgumentException("Invalid cancellation event identity");
                    cancellations.receive(id,payload.cancellationEventId());
                });
    }
    private record CancellationPayload(String eventId,Long cancellationEventId) { }

    @RabbitListener(queues = "${messaging.rabbit.name-prefix:}" + RabbitMqTopology.RESCHEDULE_NOTIFICATION_QUEUE)
    public void consumeRescheduleNotice(Message message, Channel channel) throws IOException {
        consume(message, channel, com.limitactivitysystem.reschedule.RescheduleAsyncHandler.NOTICE,
                "session.reschedule.notice.retry", ReschedulePayload.class,
                (id, payload) -> { requireReschedulePayload(id,payload); reschedules.notify(id,payload.rescheduleEventId()); });
    }

    @RabbitListener(queues = "${messaging.rabbit.name-prefix:}" + RabbitMqTopology.RESCHEDULE_RETENTION_QUEUE)
    public void consumeRescheduleRetention(Message message, Channel channel) throws IOException {
        consume(message, channel, com.limitactivitysystem.reschedule.RescheduleAsyncHandler.RETENTION,
                "session.reschedule.retain.retry", ReschedulePayload.class,
                (id, payload) -> { requireReschedulePayload(id,payload); reschedules.retain(id,payload.rescheduleEventId()); });
    }

    private void requireReschedulePayload(String id,ReschedulePayload payload) {
        if(!id.equals(payload.eventId()) || payload.rescheduleEventId()==null || payload.rescheduleEventId()<=0) {
            throw new IllegalArgumentException("Invalid reschedule event identity");
        }
    }
    private record ReschedulePayload(String eventId,Long rescheduleEventId) { }

    @RabbitListener(queues = "${messaging.rabbit.name-prefix:}" + RabbitMqTopology.ORDER_EXPIRY_QUEUE)
    public void consumeOrderExpiry(Message message, Channel channel) throws IOException {
        consume(message, channel, AsyncEventHandlerService.ORDER_EXPIRY_CONSUMER,
                "order.expired.retry", OrderExpiryPayload.class,
                (eventId, payload) -> handlers.expireOrder(eventId, payload.orderId()));
    }

    @RabbitListener(queues = "${messaging.rabbit.name-prefix:}" + RabbitMqTopology.PAYMENT_NOTIFICATION_QUEUE)
    public void consumePaymentNotification(Message message, Channel channel) throws IOException {
        consume(message, channel, AsyncEventHandlerService.PAYMENT_NOTIFICATION_CONSUMER,
                "notification.payment.retry", NotificationPayload.class,
                (eventId, payload) -> handlers.createPaymentNotification(eventId, payload.userId(), payload.content()));
    }

    @RabbitListener(queues = "${messaging.rabbit.name-prefix:}" + RabbitMqTopology.REFUND_NOTIFICATION_QUEUE)
    public void consumeRefundNotification(Message message, Channel channel) throws IOException {
        consume(message, channel, AsyncEventHandlerService.REFUND_NOTIFICATION_CONSUMER,
                "notification.refund.retry", NotificationPayload.class,
                (eventId, payload) -> handlers.createRefundNotification(eventId, payload.userId(), payload.content()));
    }

    private <T> void consume(Message message, Channel channel, String consumerName, String retryRoutingKey,
                             Class<T> payloadType, EventProcessor<T> processor) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String eventId = eventId(message);
        try {
            if (eventId == null) { throw new IllegalArgumentException("Rabbit message has no valid x-event-id"); }
            T payload = objectMapper.readValue(message.getBody(), payloadType);
            processor.process(eventId, payload);
            // The proxied transaction Service has returned, so its DB commit completed before this ACK.
            channel.basicAck(deliveryTag, false);
        } catch (Exception failure) {
            int retryCount = retryCount(message);
            int nextAttempt = retryCount + 1;
            boolean shouldRetry = eventId != null && nextAttempt < maxAttempts;
            boolean retrySent = shouldRetry && retryPublisher.publish(message, retryRoutingKey, nextAttempt);
            boolean terminal = !retrySent;
            com.limitactivitysystem.observability.BusinessTelemetry.consumerFailure(retrySent, failure);
            if (eventId != null) {
                try {
                    failures.record(consumerName, eventId, nextAttempt, terminal, compactReason(failure));
                } catch (RuntimeException ledgerFailure) {
                    failure.addSuppressed(ledgerFailure);
                    log.error("Could not record consumer failure: consumer={} eventId={} errorType={}", consumerName, eventId, ledgerFailure.getClass().getSimpleName());
                }
            }
            if (retrySent) {
                log.warn("Consumer transaction rolled back; retry scheduled: consumer={} eventId={} attempt={}",
                        consumerName, eventId, nextAttempt);
                channel.basicAck(deliveryTag, false);
            } else {
                log.error("Consumer message sent to dead-letter queue: consumer={} eventId={} attempt={}",
                        consumerName, eventId, nextAttempt);
                // Queue DLX moves this message to its durable dead-letter queue; it is never endlessly requeued.
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

    @FunctionalInterface
    private interface EventProcessor<T> { void process(String eventId, T payload) throws Exception; }

    private record OrderExpiryPayload(String eventId, Long orderId) { }
    private record NotificationPayload(String eventId, Long userId, Long orderId, String content) { }
}
