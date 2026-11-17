package com.limitactivitysystem.support;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.impl.LongStringHelper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitTestMessagesTest {
    @Test
    void nativeLongStringEventIdMatchesTheStringWrittenToTheOutbox() {
        String eventId = "667cbb8f-fc2c-4a00-8a21-6c03c822a697";
        var properties = new AMQP.BasicProperties.Builder()
                .messageId(eventId).contentType("application/json")
                .headers(Map.of("x-event-id", LongStringHelper.asLongString(eventId), "x-las-retry-count", 2))
                .build();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        var delivery = new GetResponse(new Envelope(17L, true, "test.exchange", "ticket.qualification.changed"),
                properties, body, 0);

        assertThat(RabbitTestMessages.eventId(delivery)).isEqualTo(eventId);
        var message = RabbitTestMessages.toSpringMessage(delivery);
        assertThat(message.getBody()).isEqualTo(body);
        assertThat(message.getMessageProperties().getHeaders().get("x-event-id")).isEqualTo(eventId);
        assertThat(message.getMessageProperties().getHeaders().get("x-las-retry-count")).isEqualTo(2);
        assertThat(message.getMessageProperties().getDeliveryTag()).isEqualTo(17L);
        assertThat(message.getMessageProperties().isRedelivered()).isTrue();
        assertThat(message.getMessageProperties().getReceivedExchange()).isEqualTo("test.exchange");
    }

    @Test
    void missingHeaderFallsBackToMessageIdWithoutFailingOnNullHeaders() {
        String eventId = "0bd992a6-181a-4224-b577-e3ecaffec79c";
        var delivery = new GetResponse(new Envelope(1L, false, "test.exchange", "notification.refund.success"),
                new AMQP.BasicProperties.Builder().messageId(eventId).build(), new byte[0], 0);
        assertThat(RabbitTestMessages.eventId(delivery)).isEqualTo(eventId);
    }
}
