package com.limitactivitysystem.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real durable topology, owned by one test class. No shared las.* resources are modified. */
@TestConfiguration(proxyBeanMethods = false)
@Import(ManualRabbitTestConfiguration.class)
public class IsolatedRabbitTestConfiguration {
    private final ConnectionFactory connectionFactory;
    private final RabbitAdmin admin;
    private final RabbitListenerEndpointRegistry listeners;
    private final Declarables topology;
    private final ObjectMapper objectMapper;
    private final String prefix;
    private boolean initialized;

    public IsolatedRabbitTestConfiguration(ConnectionFactory connectionFactory, RabbitAdmin admin,
            RabbitListenerEndpointRegistry listeners, @Qualifier("rabbitDeclarables") Declarables topology,
            ObjectMapper objectMapper, @Value("${messaging.rabbit.name-prefix:}") String prefix) {
        if (!prefix.matches("it\\.[a-z-]+\\.[0-9a-f]{32}\\.")) {
            throw new IllegalArgumentException("Rabbit test cleanup requires a unique it.<suite>.<uuid>. prefix");
        }
        this.connectionFactory = connectionFactory;
        this.admin = admin;
        this.listeners = listeners;
        this.topology = topology;
        this.objectMapper = objectMapper;
        this.prefix = prefix;
        topology.getDeclarablesByType(Queue.class).forEach(queue -> requireOwned(queue.getName()));
        topology.getDeclarablesByType(Exchange.class).forEach(exchange -> requireOwned(exchange.getName()));
    }

    public void prepare() {
        RabbitMqTestPrerequisites.requireConnection(connectionFactory);
        initialized = true; // Clean up partially declared topology if initialize() fails.
        admin.initialize();
        for (Queue queue : topology.getDeclarablesByType(Queue.class)) {
            var info = admin.getQueueInfo(queue.getName());
            assertThat(info).as("Rabbit test queue %s must exist", queue.getName()).isNotNull();
            assertThat(info.getMessageCount()).as("Rabbit test queue %s must be empty", queue.getName()).isZero();
            assertThat(info.getConsumerCount()).as("Rabbit test queue %s must have no other consumer", queue.getName()).isZero();
        }
    }

    public void cleanup() throws IOException {
        cleanup("after-class");
    }

    public void cleanup(String testName) throws IOException {
        if (!initialized) return; // MySQL-only cases do not need a broker.
        listeners.stop();
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            for (Queue queue : topology.getDeclarablesByType(Queue.class)) {
                var info = admin.getQueueInfo(queue.getName());
                snapshot.put(queue.getName(), info == null ? Map.of("exists", false)
                        : Map.of("messagesReady", info.getMessageCount(), "consumers", info.getConsumerCount()));
            }
            Path directory = Files.createDirectories(Path.of("target", "day21", "mq-queues",
                    prefix.substring(0, prefix.length() - 1)));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    directory.resolve(System.nanoTime() + ".json").toFile(),
                    Map.of("test", testName, "namePrefix", prefix, "queues", snapshot));
        } finally {
            // Deleting this private topology also removes delayed retries before the next fixture.
            for (Queue queue : topology.getDeclarablesByType(Queue.class)) admin.deleteQueue(queue.getName());
            for (Exchange exchange : topology.getDeclarablesByType(Exchange.class)) admin.deleteExchange(exchange.getName());
            initialized = false;
        }
    }

    private void requireOwned(String name) {
        if (!name.startsWith(prefix)) throw new IllegalArgumentException("Refusing to manage shared Rabbit resource " + name);
    }
}
