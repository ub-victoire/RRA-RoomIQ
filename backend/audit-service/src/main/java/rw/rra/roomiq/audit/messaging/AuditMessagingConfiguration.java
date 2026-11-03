package rw.rra.roomiq.audit.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "roomiq.audit.notification.events.enabled", havingValue = "true",
        matchIfMissing = true)
public class AuditMessagingConfiguration {
    public static final String EVENTS_EXCHANGE = "roomiq.events.v1";
    public static final String QUEUE = "roomiq.audit.notification.v1";
    public static final String ROUTING_KEY = "audit.notification";
    public static final String DEAD_LETTER_EXCHANGE = "roomiq.audit.notification.dlx.v1";
    public static final String DEAD_LETTER_QUEUE = "roomiq.audit.notification.dead-letter.v1";
    private static final String DEAD_LETTER_ROUTING_KEY = "audit.notification.dead-letter.v1";

    @Bean
    public DirectExchange roomIqEventsExchange() {
        return new DirectExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange auditDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue notificationAuditQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue notificationAuditDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding notificationAuditBinding(Queue notificationAuditQueue,
                                            @Qualifier("roomIqEventsExchange")
                                            DirectExchange roomIqEventsExchange) {
        return BindingBuilder.bind(notificationAuditQueue).to(roomIqEventsExchange).with(ROUTING_KEY);
    }

    @Bean
    public Binding notificationAuditDeadLetterBinding(Queue notificationAuditDeadLetterQueue,
                                                      @Qualifier("auditDeadLetterExchange")
                                                      DirectExchange auditDeadLetterExchange) {
        return BindingBuilder.bind(notificationAuditDeadLetterQueue).to(auditDeadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
    }
}
