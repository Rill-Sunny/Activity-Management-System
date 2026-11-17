package com.limitactivitysystem.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RabbitMqTopology {
    public static final String EVENTS_EXCHANGE = "las.events.exchange";
    public static final String DEAD_EXCHANGE = "las.events.dlx";
    public static final String ORDER_EXPIRY_WAIT_QUEUE = "las.order.expiry.wait";
    public static final String ORDER_EXPIRY_QUEUE = "las.order.expiry.execute";
    public static final String ORDER_EXPIRY_RETRY_QUEUE = "las.order.expiry.retry";
    public static final String ORDER_EXPIRY_DEAD_QUEUE = "las.order.expiry.dead";
    public static final String PAYMENT_NOTIFICATION_QUEUE = "las.notification.payment";
    public static final String PAYMENT_NOTIFICATION_RETRY_QUEUE = "las.notification.payment.retry";
    public static final String PAYMENT_NOTIFICATION_DEAD_QUEUE = "las.notification.payment.dead";
    public static final String REFUND_NOTIFICATION_QUEUE = "las.notification.refund";
    public static final String REFUND_NOTIFICATION_RETRY_QUEUE = "las.notification.refund.retry";
    public static final String REFUND_NOTIFICATION_DEAD_QUEUE = "las.notification.refund.dead";
    public static final String TICKET_QUALIFICATION_CHANGED_QUEUE = "las.ticket.qualification.changed";
    public static final String TICKET_QUALIFICATION_CHANGED_RETRY_QUEUE = "las.ticket.qualification.changed.retry";
    public static final String TICKET_QUALIFICATION_CHANGED_DEAD_QUEUE = "las.ticket.qualification.changed.dead";
    public static final String RESCHEDULE_NOTIFICATION_QUEUE = "las.session.reschedule.notice";
    public static final String RESCHEDULE_RETENTION_QUEUE = "las.session.reschedule.retain";
    public static final String RESCHEDULE_WAIT_QUEUE = "las.session.reschedule.wait";
    public static final String CANCELLATION_QUEUE = "las.cancellation.execute";

    @Bean
    Declarables rabbitDeclarables(@Value("${messaging.rabbit.name-prefix:}") String prefix) {
        DirectExchange events = new DirectExchange(prefix + EVENTS_EXCHANGE, true, false);
        DirectExchange dead = new DirectExchange(prefix + DEAD_EXCHANGE, true, false);

        Queue expiryWait = QueueBuilder.durable(prefix + ORDER_EXPIRY_WAIT_QUEUE)
                .deadLetterExchange(prefix + EVENTS_EXCHANGE).deadLetterRoutingKey("order.expired.execute").build();
        Queue expiry = deadLetterQueue(prefix, ORDER_EXPIRY_QUEUE, "order.expired.dead");
        Queue expiryRetry = retryQueue(prefix, ORDER_EXPIRY_RETRY_QUEUE, "order.expired.execute");
        Queue expiryDead = QueueBuilder.durable(prefix + ORDER_EXPIRY_DEAD_QUEUE).build();

        Queue payment = deadLetterQueue(prefix, PAYMENT_NOTIFICATION_QUEUE, "notification.payment.dead");
        Queue paymentRetry = retryQueue(prefix, PAYMENT_NOTIFICATION_RETRY_QUEUE, "notification.payment.success");
        Queue paymentDead = QueueBuilder.durable(prefix + PAYMENT_NOTIFICATION_DEAD_QUEUE).build();
        Queue refund = deadLetterQueue(prefix, REFUND_NOTIFICATION_QUEUE, "notification.refund.dead");
        Queue refundRetry = retryQueue(prefix, REFUND_NOTIFICATION_RETRY_QUEUE, "notification.refund.success");
        Queue refundDead = QueueBuilder.durable(prefix + REFUND_NOTIFICATION_DEAD_QUEUE).build();
        Queue qualificationChanged = deadLetterQueue(prefix, TICKET_QUALIFICATION_CHANGED_QUEUE,
                "ticket.qualification.changed.dead");
        Queue qualificationChangedRetry = retryQueue(prefix, TICKET_QUALIFICATION_CHANGED_RETRY_QUEUE,
                "ticket.qualification.changed");
        Queue qualificationChangedDead = QueueBuilder.durable(prefix + TICKET_QUALIFICATION_CHANGED_DEAD_QUEUE).build();

        Binding expiryWaitBinding = BindingBuilder.bind(expiryWait).to(events).with("order.expiry.delay");
        Binding expiryBinding = BindingBuilder.bind(expiry).to(events).with("order.expired.execute");
        Binding expiryRetryBinding = BindingBuilder.bind(expiryRetry).to(events).with("order.expired.retry");
        Binding expiryDeadBinding = BindingBuilder.bind(expiryDead).to(dead).with("order.expired.dead");
        Binding paymentBinding = BindingBuilder.bind(payment).to(events).with("notification.payment.success");
        Binding paymentRetryBinding = BindingBuilder.bind(paymentRetry).to(events).with("notification.payment.retry");
        Binding paymentDeadBinding = BindingBuilder.bind(paymentDead).to(dead).with("notification.payment.dead");
        Binding refundBinding = BindingBuilder.bind(refund).to(events).with("notification.refund.success");
        Binding refundRetryBinding = BindingBuilder.bind(refundRetry).to(events).with("notification.refund.retry");
        Binding refundDeadBinding = BindingBuilder.bind(refundDead).to(dead).with("notification.refund.dead");
        Binding qualificationChangedBinding = BindingBuilder.bind(qualificationChanged).to(events)
                .with("ticket.qualification.changed");
        Binding qualificationChangedRetryBinding = BindingBuilder.bind(qualificationChangedRetry).to(events)
                .with("ticket.qualification.changed.retry");
        Binding qualificationChangedDeadBinding = BindingBuilder.bind(qualificationChangedDead).to(dead)
                .with("ticket.qualification.changed.dead");
        var declarations = new java.util.ArrayList<org.springframework.amqp.core.Declarable>(java.util.List.of(events, dead, expiryWait, expiry, expiryRetry, expiryDead,
                payment, paymentRetry, paymentDead, refund, refundRetry, refundDead,
                expiryWaitBinding, expiryBinding, expiryRetryBinding, expiryDeadBinding,
                paymentBinding, paymentRetryBinding, paymentDeadBinding,
                refundBinding, refundRetryBinding, refundDeadBinding,
                qualificationChanged, qualificationChangedRetry, qualificationChangedDead,
                qualificationChangedBinding, qualificationChangedRetryBinding, qualificationChangedDeadBinding));
        for (String suffix : java.util.List.of("notice", "retain")) {
            String name = "las.session.reschedule." + suffix;
            String route = "session.reschedule." + suffix;
            Queue execute = deadLetterQueue(prefix, name, route + ".dead");
            Queue retry = retryQueue(prefix, name + ".retry", route);
            Queue terminal = QueueBuilder.durable(prefix + name + ".dead").build();
            declarations.addAll(java.util.List.of(execute, retry, terminal,
                    BindingBuilder.bind(execute).to(events).with(route),
                    BindingBuilder.bind(retry).to(events).with(route + ".retry"),
                    BindingBuilder.bind(terminal).to(dead).with(route + ".dead")));
        }
        Queue rescheduleWait = QueueBuilder.durable(prefix + RESCHEDULE_WAIT_QUEUE)
                .deadLetterExchange(prefix + EVENTS_EXCHANGE).deadLetterRoutingKey("session.reschedule.retain").build();
        declarations.add(rescheduleWait);
        declarations.add(BindingBuilder.bind(rescheduleWait).to(events).with("session.reschedule.wait"));
        Queue cancellation=deadLetterQueue(prefix,CANCELLATION_QUEUE,"cancellation.execute.dead");
        Queue cancellationRetry=retryQueue(prefix,CANCELLATION_QUEUE+".retry","cancellation.execute");
        Queue cancellationDead=QueueBuilder.durable(prefix+CANCELLATION_QUEUE+".dead").build();
        declarations.addAll(java.util.List.of(cancellation,cancellationRetry,cancellationDead,
                BindingBuilder.bind(cancellation).to(events).with("cancellation.execute"),
                BindingBuilder.bind(cancellationRetry).to(events).with("cancellation.execute.retry"),
                BindingBuilder.bind(cancellationDead).to(dead).with("cancellation.execute.dead")));
        return new Declarables(declarations);
    }

    private Queue deadLetterQueue(String prefix, String name, String routingKey) {
        return QueueBuilder.durable(prefix + name)
                .quorum().deliveryLimit(10)
                .deadLetterExchange(prefix + DEAD_EXCHANGE)
                .deadLetterRoutingKey(routingKey).build();
    }

    private Queue retryQueue(String prefix, String name, String targetRoutingKey) {
        return QueueBuilder.durable(prefix + name).ttl(5_000)
                .deadLetterExchange(prefix + EVENTS_EXCHANGE).deadLetterRoutingKey(targetRoutingKey).build();
    }
}
