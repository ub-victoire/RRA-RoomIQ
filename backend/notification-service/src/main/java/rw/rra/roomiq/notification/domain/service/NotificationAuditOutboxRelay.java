package rw.rra.roomiq.notification.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.notification.domain.entity.NotificationAuditOutboxEntry;
import rw.rra.roomiq.notification.domain.repository.NotificationAuditOutboxRepository;
import rw.rra.roomiq.notification.messaging.NotificationEventMessagingConfiguration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "roomiq.notification.audit.relay.enabled", havingValue = "true",
        matchIfMissing = true)
public class NotificationAuditOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(NotificationAuditOutboxRelay.class);
    private static final String ROUTING_KEY = "audit.notification";

    private final NotificationAuditOutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMillis;

    public NotificationAuditOutboxRelay(NotificationAuditOutboxRepository outboxRepository,
                                        RabbitTemplate rabbitTemplate,
                                        @Value("${roomiq.notification.audit.relay.confirm-timeout-ms:3000}")
                                        long confirmTimeoutMillis) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    @Scheduled(fixedDelayString = "${roomiq.notification.audit.relay.fixed-delay-ms:1000}")
    @Transactional
    public void publishPendingEvents() {
        Instant now = Instant.now();
        for (NotificationAuditOutboxEntry entry : outboxRepository.findPending(now, PageRequest.of(0, 50))) {
            try {
                Message message = MessageBuilder.withBody(entry.getPayload().getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json")
                        .setMessageId(entry.getEventId().toString())
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                        .build();
                CorrelationData confirmation = new CorrelationData(entry.getEventId().toString());
                rabbitTemplate.send(NotificationEventMessagingConfiguration.EVENTS_EXCHANGE,
                        ROUTING_KEY, message, confirmation);
                CorrelationData.Confirm confirm = confirmation.getFuture()
                        .get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
                if (!confirm.ack() || confirmation.getReturned() != null) {
                    throw new IllegalStateException("Notification audit event was not routed to the broker");
                }
                entry.markPublished(now);
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                long retrySeconds = Math.min(300, 1L << Math.min(entry.getAttemptCount(), 8));
                entry.deferUntil(now.plus(Duration.ofSeconds(retrySeconds)));
                log.warn("Notification audit delivery deferred eventId={} eventType={} attempt={} failureType={}",
                        entry.getEventId(), entry.getEventType(), entry.getAttemptCount(),
                        exception.getClass().getSimpleName());
            }
        }
    }
}
