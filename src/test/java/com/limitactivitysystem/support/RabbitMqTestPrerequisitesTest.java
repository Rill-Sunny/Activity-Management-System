package com.limitactivitysystem.support;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RabbitMqTestPrerequisitesTest {
    @Test
    void manualAdminIsInjectableWithoutConnectingToTheBroker() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ConnectionFactory.class, () -> factory);
            context.register(ManualRabbitTestConfiguration.class);
            context.refresh();
            assertThat(context.getBeansOfType(RabbitAdmin.class)).hasSize(1);
            assertThat(context.getBean(RabbitAdmin.class).isAutoStartup()).isFalse();
            verify(factory, never()).createConnection();
        }
    }

    @Test
    void successfulPreflightReleasesItsLogicalConnection() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        when(factory.createConnection()).thenReturn(connection);
        RabbitMqTestPrerequisites.requireConnection(factory);
        verify(connection).close();
    }

    @Test
    void unavailableBrokerFailsWithItsEndpointAndOriginalCause() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.getHost()).thenReturn("127.0.0.1");
        when(factory.getPort()).thenReturn(5672);
        var refused = new AmqpConnectException(new ConnectException("Connection refused"));
        when(factory.createConnection()).thenThrow(refused);
        assertThatThrownBy(() -> RabbitMqTestPrerequisites.requireConnection(factory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("127.0.0.1:5672")
                .hasMessageContaining("RABBITMQ_HOST/RABBITMQ_PORT")
                .hasMessageContaining("No business scenario")
                .hasCause(refused);
    }
}
