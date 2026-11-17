package com.limitactivitysystem.messaging;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqTopologyTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "it.topology.0123456789abcdef0123456789abcdef."})
    void everyBindingAndDeadLetterRouteStaysInItsOwnNamespace(String prefix) {
        var topology = new RabbitMqTopology().rabbitDeclarables(prefix);
        var queues = topology.getDeclarablesByType(Queue.class);
        var exchanges = topology.getDeclarablesByType(Exchange.class);
        var bindings = topology.getDeclarablesByType(Binding.class);
        Set<String> queueNames = queues.stream().map(Queue::getName).collect(Collectors.toSet());
        Set<String> exchangeNames = exchanges.stream().map(Exchange::getName).collect(Collectors.toSet());

        assertThat(queueNames).hasSize(23).contains(prefix + RabbitMqTopology.REFUND_NOTIFICATION_QUEUE,
                prefix + RabbitMqTopology.TICKET_QUALIFICATION_CHANGED_QUEUE,
                prefix + RabbitMqTopology.RESCHEDULE_NOTIFICATION_QUEUE,
                prefix + RabbitMqTopology.RESCHEDULE_RETENTION_QUEUE,
                prefix + RabbitMqTopology.RESCHEDULE_WAIT_QUEUE);
        assertThat(exchangeNames).containsExactlyInAnyOrder(prefix + RabbitMqTopology.EVENTS_EXCHANGE,
                prefix + RabbitMqTopology.DEAD_EXCHANGE);
        assertThat(bindings.stream().map(Binding::getDestination).collect(Collectors.toSet())).isEqualTo(queueNames);
        for (Binding binding : bindings) {
            assertThat(exchangeNames).contains(binding.getExchange());
            assertThat(binding.getRoutingKey()).doesNotStartWith("it.");
        }
        for (Queue queue : queues) {
            assertThat(queue.getName()).startsWith(prefix + "las.");
            assertThat(queue.isDurable()).isTrue(); // Broker restart tests need the same durable queues.
            assertThat(queue.isAutoDelete()).isFalse();
            assertThat(queue.isExclusive()).isFalse();
            Object deadLetterExchange = queue.getArguments().get("x-dead-letter-exchange");
            if (deadLetterExchange != null) assertThat(exchangeNames).contains(deadLetterExchange.toString());
            if (queue.getName().endsWith(".retry")) assertThat(queue.getArguments()).containsEntry("x-message-ttl", 5_000);
        }
    }
}
