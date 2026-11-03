package rw.rra.roomiq.notification.domain.service;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import rw.rra.roomiq.notification.domain.entity.NotificationAuditOutboxEntry;
import rw.rra.roomiq.notification.domain.repository.NotificationAuditOutboxRepository;
import rw.rra.roomiq.notification.messaging.NotificationEventMessagingConfiguration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationAuditOutboxRelayTests {
    private final NotificationAuditOutboxRepository repository = mock(NotificationAuditOutboxRepository.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

    @Test
    void marksOutboxPublishedOnlyAfterBrokerConfirmAndRoutingSuccess() {
        NotificationAuditOutboxEntry entry = entry();
        when(repository.findPending(any(), any())).thenReturn(List.of(entry));
        doAnswer(invocation -> {
            CorrelationData confirmation = invocation.getArgument(3);
            confirmation.getFuture().complete(new CorrelationData.Confirm(true, "confirmed"));
            return null;
        }).when(rabbitTemplate).send(eq(NotificationEventMessagingConfiguration.EVENTS_EXCHANGE),
                eq("audit.notification"), any(Message.class), any(CorrelationData.class));

        new NotificationAuditOutboxRelay(repository, rabbitTemplate, 100)
                .publishPendingEvents();

        assertThat(entry.getPublishedAt()).isNotNull();
        assertThat(entry.getAttemptCount()).isZero();
    }

    @Test
    void retainsOutboxAndSchedulesRetryWhenBrokerRejectsPublish() {
        NotificationAuditOutboxEntry entry = entry();
        when(repository.findPending(any(), any())).thenReturn(List.of(entry));
        doAnswer(invocation -> {
            CorrelationData confirmation = invocation.getArgument(3);
            confirmation.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbitTemplate).send(eq(NotificationEventMessagingConfiguration.EVENTS_EXCHANGE),
                eq("audit.notification"), any(Message.class), any(CorrelationData.class));

        new NotificationAuditOutboxRelay(repository, rabbitTemplate, 100)
                .publishPendingEvents();

        assertThat(entry.getPublishedAt()).isNull();
        assertThat(entry.getAttemptCount()).isEqualTo(1);
        assertThat(entry.getNextAttemptAt()).isAfter(Instant.now());
    }

    private static NotificationAuditOutboxEntry entry() {
        Instant now = Instant.now();
        return new NotificationAuditOutboxEntry(UUID.randomUUID(), "NotificationTemplateCreated",
                UUID.randomUUID(), UUID.randomUUID(), "correlation-1", "{}", now);
    }
}
