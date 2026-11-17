package com.limitactivitysystem.messaging;

import io.opentelemetry.api.trace.Span;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import com.limitactivitysystem.ticketorder.mapper.TicketOrderMapper;
import com.limitactivitysystem.ticketorder.model.TicketOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AsyncEventHandlerService {
    private static final Logger log = LoggerFactory.getLogger(AsyncEventHandlerService.class);
    public static final String ORDER_EXPIRY_CONSUMER = "order-expiry-v1";
    public static final String PAYMENT_NOTIFICATION_CONSUMER = "payment-notification-v1";
    public static final String REFUND_NOTIFICATION_CONSUMER = "refund-notification-v1";

    private final ConsumerProcessMapper processed;
    private final ConsumerFailureMapper failures;
    private final TicketOrderMapper orders;
    private final DatabaseTimeMapper databaseTime;
    private final NotificationMapper notifications;

    public AsyncEventHandlerService(ConsumerProcessMapper processed, ConsumerFailureMapper failures,
            TicketOrderMapper orders, DatabaseTimeMapper databaseTime, NotificationMapper notifications) {
        this.processed = processed;
        this.failures = failures;
        this.orders = orders;
        this.databaseTime = databaseTime;
        this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void expireOrder(String eventId, Long orderId) {
        if (processed.begin(ORDER_EXPIRY_CONSUMER, eventId) == 0) {
            failures.delete(ORDER_EXPIRY_CONSUMER, eventId);
            log.info("Skipped duplicate order expiry event: eventId={} orderId={}", eventId, orderId);
            return;
        }
        TicketOrder order = orders.findById(orderId);
        if (order == null) { throw new IllegalStateException("Expiry event refers to a missing order: " + orderId); }
        LocalDateTime now = databaseTime.currentTime();
        if ("PENDING_PAYMENT".equals(order.getStatus()) && order.getExpiresAt() != null
                && !now.isBefore(order.getExpiresAt())) {
            orders.closePendingIfExpired(orderId);
        }
        // An early or duplicate TTL wake-up is acknowledged; the database expiry scan is authoritative.
        failures.delete(ORDER_EXPIRY_CONSUMER, eventId);
        log.info("Processed order expiry event: eventId={} orderId={} observedStatus={} traceId={}",
                eventId, orderId, order.getStatus(), currentTraceId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void createPaymentNotification(String eventId, Long userId, String content) {
        createNotification(PAYMENT_NOTIFICATION_CONSUMER, eventId, userId, content);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void createRefundNotification(String eventId, Long userId, String content) {
        createNotification(REFUND_NOTIFICATION_CONSUMER, eventId, userId, content);
    }

    private void createNotification(String consumerName, String eventId, Long userId, String content) {
        if (userId == null || userId <= 0 || content == null || content.isBlank()) {
            throw new IllegalArgumentException("Notification user and content are required");
        }
        if (processed.begin(consumerName, eventId) == 1) {
            notifications.insert(userId, eventId, content);
        }
        failures.delete(consumerName, eventId);
        log.info("Processed notification event: eventId={} consumer={} userId={} traceId={}",
                eventId, consumerName, userId, currentTraceId());
    }

    private String currentTraceId() {
        var context = Span.current().getSpanContext();
        return context.isValid() ? context.getTraceId() : "-";
    }
}
