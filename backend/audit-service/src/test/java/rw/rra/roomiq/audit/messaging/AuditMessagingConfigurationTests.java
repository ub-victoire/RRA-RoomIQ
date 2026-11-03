package rw.rra.roomiq.audit.messaging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditMessagingConfigurationTests {
    private final AuditMessagingConfiguration configuration = new AuditMessagingConfiguration();

    @Test
    void declaresDurableAuditQueueAndDeadLetterRoute() {
        var queue = configuration.notificationAuditQueue();
        var deadLetterQueue = configuration.notificationAuditDeadLetterQueue();

        assertThat(queue.getName()).isEqualTo(AuditMessagingConfiguration.QUEUE);
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", AuditMessagingConfiguration.DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", "audit.notification.dead-letter.v1");
        assertThat(deadLetterQueue.getName()).isEqualTo(AuditMessagingConfiguration.DEAD_LETTER_QUEUE);
        assertThat(deadLetterQueue.isDurable()).isTrue();
        assertThat(configuration.notificationAuditBinding(queue, configuration.roomIqEventsExchange())
                .getRoutingKey()).isEqualTo("audit.notification");
    }
}
