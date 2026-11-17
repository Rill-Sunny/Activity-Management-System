package com.limitactivitysystem.messaging.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.limitactivitysystem.messaging.AsyncEventConsumer;
import com.limitactivitysystem.messaging.AsyncEventHandlerService;
import com.limitactivitysystem.messaging.RabbitMqTopology;
import com.limitactivitysystem.outbox.OutboxClaimService;
import com.limitactivitysystem.outbox.OutboxEvent;
import com.limitactivitysystem.outbox.OutboxEventWriter;
import com.limitactivitysystem.outbox.OutboxPublisher;
import com.limitactivitysystem.outbox.OutboxRelayService;
import com.limitactivitysystem.outbox.OutboxTraceSupport;
import com.limitactivitysystem.security.JwtTestTokens;
import com.limitactivitysystem.ticketorder.mapper.DatabaseTimeMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.limitactivitysystem.support.RequiresEnvironmentVariable;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * These tests use a random isolated MySQL schema and a live RabbitMQ broker.
 * Set RUN_MQ_RELIABILITY_TESTS=true only against a disposable test database and broker.
 */
@SpringBootTest(properties = {
        JwtTestTokens.SECRET_PROPERTY,
        "spring.flyway.enabled=false",
        "spring.rabbitmq.dynamic=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "outbox.relay.initial-delay-ms=3600000",
        "outbox.relay.scan-delay-ms=3600000",
        "ticket-order.expiration.initial-delay-ms=3600000",
        "ticket-order.expiration.scan-delay-ms=3600000"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import({com.limitactivitysystem.activity.integration.CurrentSchemaTestConfiguration.class,
        com.limitactivitysystem.support.IsolatedRabbitTestConfiguration.class})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@RequiresEnvironmentVariable(named = "RUN_MQ_RELIABILITY_TESTS", matches = "true")
class RabbitMqOutboxReliabilityMySqlIntegrationTest {
    private static final String RABBIT_NAME_PREFIX = "it.reliability." + UUID.randomUUID().toString().replace("-", "") + ".";
    private static final int OUTBOX_MAX_ATTEMPTS = 8;
    private static final String PAYMENT_QUEUE = rabbitName(RabbitMqTopology.PAYMENT_NOTIFICATION_QUEUE);
    private static final String PAYMENT_RETRY_QUEUE = rabbitName(RabbitMqTopology.PAYMENT_NOTIFICATION_RETRY_QUEUE);
    private static final String PAYMENT_DEAD_QUEUE = rabbitName(RabbitMqTopology.PAYMENT_NOTIFICATION_DEAD_QUEUE);
    private static final String PAYMENT_ROUTE = "notification.payment.success";
    private static final String REFUND_QUEUE = rabbitName(RabbitMqTopology.REFUND_NOTIFICATION_QUEUE);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ConnectionFactory rabbitConnectionFactory;
    @Autowired private RabbitAdmin rabbitAdmin;
    @Autowired private com.limitactivitysystem.support.IsolatedRabbitTestConfiguration rabbitFixture;
    @Autowired private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
    @Autowired private OutboxEventWriter outboxWriter;
    @Autowired private OutboxClaimService claims;
    @Autowired private OutboxPublisher publisher;
    @Autowired private OutboxRelayService relay;
    @Autowired private OutboxTraceSupport traceSupport;
    @Autowired private DatabaseTimeMapper databaseTime;
    @Autowired private AsyncEventConsumer consumer;
    @Autowired private AsyncEventHandlerService handlers;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private MockMvc mvc;
    @Autowired private org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listeners;

    private final List<String> eventIds = new ArrayList<>();
    private final List<String> orderRequestIds = new ArrayList<>();
    private final List<Long> testUserIds = new ArrayList<>();

    @org.junit.jupiter.api.BeforeAll
    static void requireBrokerBeforeRunningScenarios(@Autowired ConnectionFactory connectionFactory) {
        com.limitactivitysystem.support.RabbitMqTestPrerequisites.requireConnection(connectionFactory);
    }

    @org.springframework.test.context.DynamicPropertySource
    static void configureRabbitNamespace(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("messaging.rabbit.name-prefix", () -> RABBIT_NAME_PREFIX);
    }

    private static String rabbitName(String name) { return RABBIT_NAME_PREFIX + name; }

    @AfterEach
    void clearRabbitNamespace(org.junit.jupiter.api.TestInfo info) throws Exception {
        rabbitFixture.cleanup(info.getDisplayName());
    }

    @org.junit.jupiter.api.AfterAll
    static void removeRabbitNamespace(@Autowired com.limitactivitysystem.support.IsolatedRabbitTestConfiguration fixture)
            throws Exception { fixture.cleanup(); }

    @BeforeEach
    void requireEmptyMessagingQueues() {
        rabbitFixture.prepare();
    }

    @AfterEach
    void removeOnlyThisTestData() {
        for (String eventId : eventIds) {
            jdbc.update("DELETE FROM notification WHERE event_id = ?", eventId);
            jdbc.update("DELETE FROM consumer_failure WHERE event_id = ?", eventId);
            jdbc.update("DELETE FROM consumer_process WHERE event_id = ?", eventId);
            jdbc.update("DELETE FROM outbox_event WHERE event_id = ?", eventId);
        }
        for (String requestId : orderRequestIds) {
            jdbc.update("DELETE FROM ticket_order_item WHERE order_id = (SELECT order_id FROM ticket_order WHERE request_id = ?)", requestId);
            jdbc.update("DELETE FROM ticket_order WHERE request_id = ?", requestId);
        }
        for (Long userId : testUserIds) {
            jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        }
    }

    @Test
    void brokerConnectionFailureLeavesOutboxRetryableAndLiveBrokerRecoversIt() throws Exception {
        String eventId = paymentEvent(900_000_101L, "broker recovery " + UUID.randomUUID());
        int unavailablePort = unusedPort();
        CachingConnectionFactory unavailableFactory = new CachingConnectionFactory("127.0.0.1");
        unavailableFactory.setPort(unavailablePort);
        unavailableFactory.setConnectionTimeout(300);
        unavailableFactory.setPublisherReturns(true);
        var unavailableTemplate = new org.springframework.amqp.rabbit.core.RabbitTemplate(unavailableFactory);
        unavailableTemplate.setMandatory(true);
        var unavailablePublisher = new OutboxPublisher(unavailableTemplate, objectMapper, databaseTime,
                traceSupport, 500, RABBIT_NAME_PREFIX);
        try {
            new OutboxRelayService(claims, unavailablePublisher, 1, OUTBOX_MAX_ATTEMPTS, 60)
                    .publishClaimedEvents();
        } finally {
            unavailableFactory.destroy();
        }

        assertThat(outboxStatus(eventId)).isEqualTo("PENDING");
        assertThat(outboxFailure(eventId)).isNotBlank();

        jdbc.update("UPDATE outbox_event SET next_attempt_at = CURRENT_TIMESTAMP(3) WHERE event_id = ?", eventId);
        relay.publishClaimedEvents();
        assertThat(outboxStatus(eventId)).isEqualTo("SENT");

        try (ChannelSession session = openChannel()) {
            GetResponse delivery = awaitDelivery(session.channel(), PAYMENT_QUEUE, Duration.ofSeconds(5));
            assertThat(eventId(delivery)).isEqualTo(eventId);
            consumer.consumePaymentNotification(toSpringMessage(delivery), session.channel());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
    }

    @Test
    void publisherReturnForUnroutableMessageNeverMarksOutboxSent() throws Exception {
        String eventId = paymentEvent(900_000_102L, "unroutable " + UUID.randomUUID());
        Binding paymentBinding = new Binding(PAYMENT_QUEUE, Binding.DestinationType.QUEUE,
                rabbitName(RabbitMqTopology.EVENTS_EXCHANGE), PAYMENT_ROUTE, null);
        rabbitAdmin.removeBinding(paymentBinding);
        try {
            var correlation = new CorrelationData(UUID.randomUUID().toString());
            var unroutable = new org.springframework.amqp.core.Message(
                    "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    new org.springframework.amqp.core.MessageProperties());
            rabbitTemplate.send(rabbitName(RabbitMqTopology.EVENTS_EXCHANGE), "unbound." + UUID.randomUUID(),
                    unroutable, correlation);
            assertThat(correlation.getFuture().get(5, TimeUnit.SECONDS).isAck()).isTrue();
            assertThat(correlation.getReturned()).isNotNull();

            relay.publishClaimedEvents();
        } finally {
            rabbitAdmin.declareBinding(paymentBinding);
        }

        assertThat(outboxStatus(eventId)).isEqualTo("PENDING");
        // The returned message above proves the routing failure; its raw text is not persisted.
        assertThat(outboxFailure(eventId)).isEqualTo("IllegalStateException");
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox_event WHERE event_id = ?",
                Boolean.class, eventId)).isTrue();
    }

    @Test
    void confirmedPublishLeaseExpiryAndConsumerRedeliveryKeepOneBusinessEffect() throws Exception {
        long userId = 900_000_103L;
        String eventId = paymentEvent(userId, "deduplicated " + UUID.randomUUID());

        OutboxEvent firstClaim = claimOne();
        assertThat(firstClaim.getEventId()).isEqualTo(eventId);
        publisher.publish(firstClaim); // Broker confirms; simulate relay death before SENT update.

        try (ChannelSession firstDelivery = openChannel()) {
            GetResponse delivery = awaitDelivery(firstDelivery.channel(), PAYMENT_QUEUE, Duration.ofSeconds(5));
            assertThat(eventId(delivery)).isEqualTo(eventId);
            handlers.createPaymentNotification(eventId, userId, "deduplicated " + eventId);
            // Closing this channel without ACK emulates a consumer crash after the DB commit.
        }

        jdbc.update("UPDATE outbox_event SET lease_until = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE event_id = ?",
                eventId);
        OutboxEvent reclaimed = claimOne();
        assertThat(reclaimed.getEventId()).isEqualTo(eventId);
        assertThat(reclaimed.getAttempts()).isEqualTo(2);
        assertThat(reclaimed.getLeaseToken()).isNotEqualTo(firstClaim.getLeaseToken());
        publisher.publish(reclaimed);
        claims.markSent(reclaimed);

        List<Boolean> redeliveries = new ArrayList<>();
        try (ChannelSession session = openChannel()) {
            for (int deliveryIndex = 0; deliveryIndex < 2; deliveryIndex++) {
                GetResponse delivery = awaitDelivery(session.channel(), PAYMENT_QUEUE, Duration.ofSeconds(5));
                assertThat(eventId(delivery)).isEqualTo(eventId);
                redeliveries.add(delivery.getEnvelope().isRedeliver());
                consumer.consumePaymentNotification(toSpringMessage(delivery), session.channel());
            }
            assertThat(session.channel().basicGet(PAYMENT_QUEUE, false)).isNull();
        }

        assertThat(redeliveries).contains(true);
        assertThat(outboxStatus(eventId)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumer_process WHERE consumer_name = ? AND event_id = ?",
                Integer.class, AsyncEventHandlerService.PAYMENT_NOTIFICATION_CONSUMER, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
    }

    @Test
    void ttlDeadLetterTriggersExpiryConsumerButCannotClosePaidOrder() throws Exception {
        long orderId = insertOrder("PAID");
        String eventId = appendEvent("ORDER_EXPIRED", orderId,
                Map.of("orderId", orderId, "expiresAt", databaseTime.currentTime().minusMinutes(1).toString()));
        OutboxEvent event = claimOne();
        assertThat(event.getEventId()).isEqualTo(eventId);
        publisher.publish(event);
        claims.markSent(event);

        try (ChannelSession session = openChannel()) {
            GetResponse delivery = awaitDelivery(session.channel(), rabbitName(RabbitMqTopology.ORDER_EXPIRY_QUEUE),
                    Duration.ofSeconds(10));
            assertThat(eventId(delivery)).isEqualTo(eventId);
            consumer.consumeOrderExpiry(toSpringMessage(delivery), session.channel());
        }
        assertThat(jdbc.queryForObject("SELECT status FROM ticket_order WHERE order_id = ?", String.class, orderId))
                .isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumer_process WHERE consumer_name = ? AND event_id = ?",
                Integer.class, AsyncEventHandlerService.ORDER_EXPIRY_CONSUMER, eventId)).isEqualTo(1);
    }

    @Test
    void repeatedConsumerFailuresAreBoundedAndDeadLetteredWithQueryableReason() throws Exception {
        Map<String, Object> invalidPayload = new HashMap<>();
        invalidPayload.put("userId", 0);
        invalidPayload.put("content", " ");
        String eventId = appendEvent("PAYMENT_SUCCEEDED", 1L, invalidPayload);
        OutboxEvent event = claimOne();
        assertThat(event.getEventId()).isEqualTo(eventId);
        publisher.publish(event);
        claims.markSent(event);

        List<Integer> retryCounts = new ArrayList<>();
        try (ChannelSession session = openChannel()) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                GetResponse delivery = awaitDelivery(session.channel(), PAYMENT_QUEUE, Duration.ofSeconds(8));
                assertThat(eventId(delivery)).isEqualTo(eventId);
                retryCounts.add(retryCount(delivery));
                consumer.consumePaymentNotification(toSpringMessage(delivery), session.channel());
            }
            GetResponse dead = awaitDelivery(session.channel(), PAYMENT_DEAD_QUEUE, Duration.ofSeconds(5));
            assertThat(eventId(dead)).isEqualTo(eventId);
            session.channel().basicAck(dead.getEnvelope().getDeliveryTag(), false);
        }

        assertThat(retryCounts).containsExactly(0, 1, 2);
        assertThat(jdbc.queryForObject("SELECT attempts FROM consumer_failure WHERE consumer_name = ? AND event_id = ?",
                Integer.class, AsyncEventHandlerService.PAYMENT_NOTIFICATION_CONSUMER, eventId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM consumer_failure WHERE consumer_name = ? AND event_id = ?",
                String.class, AsyncEventHandlerService.PAYMENT_NOTIFICATION_CONSUMER, eventId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_error FROM consumer_failure WHERE consumer_name = ? AND event_id = ?",
                String.class, AsyncEventHandlerService.PAYMENT_NOTIFICATION_CONSUMER, eventId)).isEqualTo("IllegalArgumentException");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id = ?", Integer.class, eventId))
                .isZero();
    }

    @Test
    void repeatedRefundNotificationCreatesOnlyOneNotification() throws Exception {
        long userId = 900_000_105L;
        String content = "refund complete " + UUID.randomUUID();
        String eventId = appendEvent("REFUND_SUCCEEDED", 1L,
                Map.of("userId", userId, "content", content));
        OutboxEvent event = claimOne();
        assertThat(event.getEventId()).isEqualTo(eventId);
        publisher.publish(event);
        publisher.publish(event); // Broker receives the same business event twice.
        claims.markSent(event);

        try (ChannelSession session = openChannel()) {
            for (int i = 0; i < 2; i++) {
                GetResponse delivery = awaitDelivery(session.channel(), REFUND_QUEUE, Duration.ofSeconds(5));
                assertThat(eventId(delivery)).isEqualTo(eventId);
                consumer.consumeRefundNotification(toSpringMessage(delivery), session.channel());
            }
            assertThat(session.channel().basicGet(REFUND_QUEUE, false)).isNull();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumer_process WHERE consumer_name = ? AND event_id = ?",
                Integer.class, AsyncEventHandlerService.REFUND_NOTIFICATION_CONSUMER, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ? AND event_id = ?",
                Integer.class, userId, eventId)).isEqualTo(1);
    }

    @Test
    void publisherCanReplayFailedEventWithOriginalIdAndCannotReplayCommittedEffect() throws Exception {
        long userId = 900_000_104L;
        String eventId = paymentEvent(userId, "replayed " + UUID.randomUUID());
        jdbc.update("UPDATE outbox_event SET status = 'FAILED', failure_reason = 'injected relay failure' WHERE event_id = ?",
                eventId);
        jdbc.update("""
                INSERT INTO consumer_failure (consumer_name, event_id, attempts, status, last_error)
                VALUES (?, ?, 3, 'FAILED', 'injected consumer failure')
                """, AsyncEventHandlerService.PAYMENT_NOTIFICATION_CONSUMER, eventId);
        long publisherId = insertUser("PUBLISHER");
        String publisherBearer = "Bearer " + JwtTestTokens.token(jwtEncoder, publisherId, "PUBLISHER");
        mvc.perform(post("/admin/outbox-events/{eventId}/replay", eventId)
                        .header("Authorization", publisherBearer))
                .andExpect(status().isForbidden());

        long adminId = insertUser("ADMIN");
        String adminBearer = "Bearer " + JwtTestTokens.token(jwtEncoder, adminId, "ADMIN");

        mvc.perform(post("/admin/outbox-events/{eventId}/replay", eventId)
                        .header("Authorization", adminBearer))
                .andExpect(status().isAccepted());
        assertThat(outboxStatus(eventId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE event_id = ?", String.class, eventId))
                .isEqualTo(eventId);

        relay.publishClaimedEvents();
        assertThat(outboxStatus(eventId)).isEqualTo("SENT");
        try (ChannelSession session = openChannel()) {
            GetResponse delivery = awaitDelivery(session.channel(), PAYMENT_QUEUE, Duration.ofSeconds(5));
            assertThat(eventId(delivery)).isEqualTo(eventId);
            consumer.consumePaymentNotification(toSpringMessage(delivery), session.channel());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
        mvc.perform(post("/admin/outbox-events/{eventId}/replay", eventId)
                        .header("Authorization", adminBearer))
                .andExpect(status().isConflict());
    }

    @Test @RequiresEnvironmentVariable(named = "RUN_DAY21_FAULT_TESTS", matches = "true")
    @org.junit.jupiter.api.Timeout(180)
    void realBrokerStopRetainsCommittedOutboxAndRecoveryDeliversIt() throws Exception {
        String eventId = paymentEvent(insertUser("USER"), "day21 actual broker stop");
        try (var fault = new com.limitactivitysystem.support.ComposeServiceFault("rabbitmq")) {
            fault.stop();
            relay.publishClaimedEvents();
            assertThat(outboxStatus(eventId)).isEqualTo("PENDING");
            assertThat(outboxFailure(eventId)).isNotBlank();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumer_process WHERE event_id=?", Integer.class, eventId)).isZero();
            saveEvidence("broker-stopped",List.of(eventId));
            fault.start();
            com.limitactivitysystem.support.ComposeServiceFault.await("RabbitMQ recovery", Duration.ofSeconds(60), () -> {
                try { return rabbitAdmin.getQueueInfo(PAYMENT_QUEUE) != null; }
                catch (org.springframework.amqp.AmqpException unavailable) { return false; }
            });
            jdbc.update("UPDATE outbox_event SET next_attempt_at=CURRENT_TIMESTAMP(3) WHERE event_id=?", eventId);
            relay.publishClaimedEvents();
            assertThat(outboxStatus(eventId)).isEqualTo("SENT");
            try (ChannelSession session = openChannel()) {
                GetResponse delivery = awaitDelivery(session.channel(), PAYMENT_QUEUE, Duration.ofSeconds(10));
                assertThat(eventId(delivery)).isEqualTo(eventId);
                consumer.consumePaymentNotification(toSpringMessage(delivery), session.channel());
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id=?", Integer.class, eventId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumer_process WHERE event_id=?", Integer.class, eventId)).isEqualTo(1);
            saveEvidence("broker-recovered",List.of(eventId));
        }
    }

    @Test
    void stoppedListenerAccumulatesMessagesAndRealListenerRestartDrainsThem() throws Exception {
        listeners.start();
        listeners.stop();
        List<String> batch = new ArrayList<>();
        long userId = insertUser("USER");
        for (int i=0;i<3;i++) batch.add(paymentEvent(userId, "day21 backlog " + i));
        relay.publishClaimedEvents();
        com.limitactivitysystem.support.ComposeServiceFault.await("three queued notifications", Duration.ofSeconds(10), () -> {
            var queue = rabbitAdmin.getQueueInfo(PAYMENT_QUEUE);
            return queue != null && queue.getMessageCount() == 3;
        });
        for (String id : batch) {
            assertThat(outboxStatus(id)).isEqualTo("SENT");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumer_process WHERE event_id=?", Integer.class, id)).isZero();
        }
        saveEvidence("listener-stopped",batch);
        try {
            listeners.start();
            com.limitactivitysystem.support.ComposeServiceFault.await("notification effects", Duration.ofSeconds(15), () ->
                    batch.stream().allMatch(id -> jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id=?", Integer.class, id) == 1));
        } finally { listeners.stop(); }
        for (String id : batch) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumer_process WHERE consumer_name=? AND event_id=?", Integer.class,
                    AsyncEventHandlerService.PAYMENT_NOTIFICATION_CONSUMER,id)).isEqualTo(1);
        }
        assertThat(rabbitAdmin.getQueueInfo(PAYMENT_QUEUE).getMessageCount()).isZero();
        saveEvidence("listener-recovered",batch);
    }

    private void saveEvidence(String label,List<String> ids) throws Exception {
        var records=new ArrayList<Object>();
        for(String id:ids) records.add(Map.of("outbox",jdbc.queryForMap("SELECT event_id,event_type,status,attempts,failure_reason,traceparent FROM outbox_event WHERE event_id=?",id),
                "consumerProcess",jdbc.queryForList("SELECT * FROM consumer_process WHERE event_id=?",id),
                "consumerFailure",jdbc.queryForList("SELECT * FROM consumer_failure WHERE event_id=?",id),
                "notifications",jdbc.queryForList("SELECT notification_id,event_id,user_id FROM notification WHERE event_id=?",id)));
        var directory=java.nio.file.Files.createDirectories(java.nio.file.Path.of("target","day21","mq"));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(label+".json").toFile(),records);
    }

    private String paymentEvent(long userId, String content) {
        return appendEvent("PAYMENT_SUCCEEDED", 1L, Map.of("userId", userId, "content", content));
    }

    private String appendEvent(String eventType, long aggregateId, Map<String, ?> payload) {
        String eventId = new TransactionTemplate(transactionManager)
                .execute(status -> outboxWriter.append(eventType, aggregateId, payload));
        eventIds.add(eventId);
        return eventId;
    }

    private OutboxEvent claimOne() {
        List<OutboxEvent> batch = claims.claimBatch(1, OUTBOX_MAX_ATTEMPTS, 60);
        assertThat(batch).hasSize(1);
        return batch.get(0);
    }

    private String outboxStatus(String eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?", String.class, eventId);
    }

    private String outboxFailure(String eventId) {
        return jdbc.queryForObject("SELECT failure_reason FROM outbox_event WHERE event_id = ?", String.class, eventId);
    }

    private long insertOrder(String status) {
        String requestId = "mq-reliability-" + UUID.randomUUID();
        orderRequestIds.add(requestId);
        jdbc.update("""
                INSERT INTO ticket_order (buyer_id, session_id, request_id, total_amount, status, created_at, paid_at)
                VALUES (1, 1, ?, 1.00, ?, DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 30 MINUTE),
                    CASE WHEN ? = 'PAID' THEN DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 29 MINUTE) ELSE NULL END)
                """, requestId, status, status);
        return jdbc.queryForObject("SELECT order_id FROM ticket_order WHERE request_id = ?", Long.class, requestId);
    }

    private long insertUser(String role) {
        String username = "mq_" + role.toLowerCase() + "_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO app_user (username, password_hash, role) VALUES (?, 'integration-only', ?)",
                username, role);
        long id = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
        testUserIds.add(id);
        return id;
    }

    private int unusedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private ChannelSession openChannel() {
        var connection = rabbitConnectionFactory.createConnection();
        return new ChannelSession(connection, connection.createChannel(false));
    }

    private GetResponse awaitDelivery(Channel channel, String queue, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            GetResponse delivery = channel.basicGet(queue, false);
            if (delivery != null) {
                return delivery;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No message arrived in " + queue + " within " + timeout);
    }

    private String eventId(GetResponse delivery) {
        return com.limitactivitysystem.support.RabbitTestMessages.eventId(delivery);
    }

    private int retryCount(GetResponse delivery) {
        Object value = delivery.getProps().getHeaders().get("x-las-retry-count");
        return value instanceof Number number ? number.intValue() : 0;
    }

    private org.springframework.amqp.core.Message toSpringMessage(GetResponse delivery) {
        return com.limitactivitysystem.support.RabbitTestMessages.toSpringMessage(delivery);
    }

    private static final class ChannelSession implements AutoCloseable {
        private final org.springframework.amqp.rabbit.connection.Connection connection;
        private final Channel channel;

        private ChannelSession(org.springframework.amqp.rabbit.connection.Connection connection, Channel channel) {
            this.connection = connection;
            this.channel = channel;
        }

        private Channel channel() { return channel; }

        @Override
        public void close() throws Exception {
            try {
                com.limitactivitysystem.support.RabbitTestMessages.closeChannel(channel);
            } finally {
                connection.close();
            }
        }
    }
}
