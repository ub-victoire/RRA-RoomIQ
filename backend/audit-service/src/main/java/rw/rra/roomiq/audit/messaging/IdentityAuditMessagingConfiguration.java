package rw.rra.roomiq.audit.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "roomiq.audit.identity.events.enabled", havingValue = "true", matchIfMissing = true)
public class IdentityAuditMessagingConfiguration {
    public static final String EVENTS_EXCHANGE = "roomiq.events.v1";
    public static final String QUEUE = "roomiq.audit.identity.v1";
    public static final String ROUTING_KEY = "audit.identity";
    public static final String DEAD_LETTER_EXCHANGE = "roomiq.audit.identity.dlx.v1";
    public static final String DEAD_LETTER_QUEUE = "roomiq.audit.identity.dead-letter.v1";
    public static final String DEAD_LETTER_ROUTING_KEY = "audit.identity.dead-letter.v1";

    @Bean
    public DirectExchange identityAuditEventsExchange() {
        return new DirectExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange identityAuditDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue identityAuditQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue identityAuditDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding identityAuditBinding(Queue identityAuditQueue,
                                        @Qualifier("identityAuditEventsExchange")
                                        DirectExchange identityAuditEventsExchange) {
        return BindingBuilder.bind(identityAuditQueue).to(identityAuditEventsExchange).with(ROUTING_KEY);
    }

    @Bean
    public Binding identityAuditDeadLetterBinding(Queue identityAuditDeadLetterQueue,
                                                  @Qualifier("identityAuditDeadLetterExchange")
                                                  DirectExchange identityAuditDeadLetterExchange) {
        return BindingBuilder.bind(identityAuditDeadLetterQueue)
                .to(identityAuditDeadLetterExchange).with(DEAD_LETTER_ROUTING_KEY);
    }
}
