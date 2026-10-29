package com.datum.clientmanagement.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Durable RabbitMQ topology owned by this service (ticket 06 AC-8, ticket 07 AC-8). Spring's {@code RabbitAdmin}
 * declares these beans on every new connection. They are also declared as soon as the app is ready instead of on the
 * first publish.
 * RabbitMQ being down then must not stop the app (NFR-10): the failure is logged, and {@code RabbitAdmin} declares
 * everything on the next connection.
 */
@Configuration(proxyBeanMethods = false)
public class MessagingTopology {

    public static final String CUSTOMER_EVENTS_EXCHANGE = "customer.events";
    public static final String CUSTOMER_CREATED_ROUTING_KEY = "customer.created";
    public static final String CUSTOMER_CREATED_QUEUE = "customer.created";
    public static final String STATUS_CHANGE_ROUTING_KEY = "customer.status.change";
    public static final String STATUS_CHANGE_QUEUE = "customer.status-change";
    public static final String DEAD_LETTER_EXCHANGE = "customer.dlx";
    public static final String STATUS_CHANGE_DEAD_LETTER_QUEUE = "customer.status-change.dlq";

    private static final Logger log = LoggerFactory.getLogger(MessagingTopology.class);

    @Bean
    TopicExchange customerEventsExchange() {
        return ExchangeBuilder.topicExchange(CUSTOMER_EVENTS_EXCHANGE).durable(true).build();
    }

    // Declared by the publisher side so events can be inspected even before any consumer exists.
    @Bean
    Queue customerCreatedQueue() {
        return QueueBuilder.durable(CUSTOMER_CREATED_QUEUE).build();
    }

    @Bean
    Binding customerCreatedBinding(TopicExchange customerEventsExchange, Queue customerCreatedQueue) {
        return BindingBuilder.bind(customerCreatedQueue).to(customerEventsExchange).with(CUSTOMER_CREATED_ROUTING_KEY);
    }

    // Ticket 07 AC-8: rejected status changes (poison or retries exhausted) are dead-lettered to the DLQ. The explicit
    // dead-letter routing key keeps that working however the message was routed into the queue.
    @Bean
    DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    @Bean
    Queue statusChangeQueue() {
        return QueueBuilder.durable(STATUS_CHANGE_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(STATUS_CHANGE_DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    Binding statusChangeBinding(TopicExchange customerEventsExchange, Queue statusChangeQueue) {
        return BindingBuilder.bind(statusChangeQueue).to(customerEventsExchange).with(STATUS_CHANGE_ROUTING_KEY);
    }

    @Bean
    Queue statusChangeDeadLetterQueue() {
        return QueueBuilder.durable(STATUS_CHANGE_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding statusChangeDeadLetterBinding(DirectExchange deadLetterExchange, Queue statusChangeDeadLetterQueue) {
        return BindingBuilder.bind(statusChangeDeadLetterQueue).to(deadLetterExchange)
                .with(STATUS_CHANGE_DEAD_LETTER_QUEUE);
    }

    // A listener bean rather than an @EventListener method: AmqpAdmin arrives as a bean-method argument, so this
    // configuration class never depends on AmqpAdmin, which itself collects the declarables defined above.
    @Bean
    ApplicationListener<ApplicationReadyEvent> declareTopologyOnStartup(AmqpAdmin amqpAdmin) {
        return event -> declareTopology(amqpAdmin);
    }

    private static void declareTopology(AmqpAdmin amqpAdmin) {
        try {
            amqpAdmin.initialize();
        } catch (AmqpException exception) {
            log.warn("RabbitMQ topology not declared at startup; it will be declared on the next connection: {}",
                    exception.getMessage());
        }
    }
}
