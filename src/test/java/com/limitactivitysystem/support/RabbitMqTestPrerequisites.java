package com.limitactivitysystem.support;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

public final class RabbitMqTestPrerequisites {
    private RabbitMqTestPrerequisites() { }

    public static void requireConnection(ConnectionFactory factory) {
        try {
            // Opening an AMQP connection also verifies authentication, beyond a TCP port probe.
            var connection = factory.createConnection();
            connection.close();
        } catch (AmqpException unavailable) {
            throw new IllegalStateException(
                    "RabbitMQ test prerequisite failed at " + factory.getHost() + ":" + factory.getPort()
                    + ". Start the existing test broker or configure RABBITMQ_HOST/RABBITMQ_PORT"
                    + " (AMQP port, normally 5672; 15672 is the management port)."
                    + " A running Windows service or management UI does not prove the AMQP listener is ready;"
                    + " inspect the broker startup log and rabbitmq-diagnostics listeners."
                    + " Also check the configured credentials and virtual host."
                    + " No business scenario or intentional broker-stop experiment has started.", unavailable);
        }
    }
}
