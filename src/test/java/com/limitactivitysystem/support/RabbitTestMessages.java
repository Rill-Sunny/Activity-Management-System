package com.limitactivitysystem.support;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.RabbitUtils;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public final class RabbitTestMessages {
    private RabbitTestMessages() { }

    public static Message toSpringMessage(GetResponse delivery) {
        // Match the listener container's conversion, including AMQP LongString headers.
        var properties = new DefaultMessagePropertiesConverter().toMessageProperties(
                delivery.getProps(), delivery.getEnvelope(), "UTF-8");
        return new Message(delivery.getBody(), properties);
    }

    public static String eventId(GetResponse delivery) {
        var properties = toSpringMessage(delivery).getMessageProperties();
        Object header = properties.getHeader("x-event-id");
        return header == null ? properties.getMessageId() : header.toString();
    }

    public static void closeChannel(Channel channel) throws IOException, TimeoutException {
        // A cached channel's ordinary close() can keep unacked deliveries on the broker.
        RabbitUtils.setPhysicalCloseRequired(channel, true);
        try {
            channel.close();
        } finally {
            RabbitUtils.clearPhysicalCloseRequired();
        }
    }
}
