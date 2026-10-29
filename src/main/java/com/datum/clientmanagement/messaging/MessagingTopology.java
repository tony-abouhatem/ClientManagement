package com.datum.clientmanagement.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Durable RabbitMQ topology owned by this service (ticket 06 AC-8). Spring's {@code RabbitAdmin} declares these
 * beans on every new connection. They are also declared as soon as the app is ready instead of on the first publish.
 * RabbitMQ being down then must not stop the app (NFR-10): the failure is logged, and {@code RabbitAdmin} declares
 * everything on the next connection.
 */
@Configuration(proxyBeanMethods = false)
public class MessagingTopology {

    public static final String CUSTOMER_EVENTS_EXCHANGE = "customer.events";
    public static final String CUSTOMER_CREATED_ROUTING_KEY = "customer.created";
    public static final String CUSTOMER_CREATED_QUEUE = "customer.created";

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
