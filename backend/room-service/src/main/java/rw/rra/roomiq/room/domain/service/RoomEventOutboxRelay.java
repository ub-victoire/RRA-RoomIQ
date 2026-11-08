package rw.rra.roomiq.room.domain.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.room.config.RoomEventMessagingConfiguration;
import rw.rra.roomiq.room.domain.entity.RoomEventOutboxEntry;
import rw.rra.roomiq.room.domain.repository.RoomEventOutboxRepository;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "room.events.relay.enabled", havingValue = "true", matchIfMissing = true)
public class RoomEventOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(RoomEventOutboxRelay.class);
    private static final int OPERATIONAL_ALERT_ATTEMPT = 10;

    private final RoomEventOutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMillis;
    private final Clock clock;
    private final Counter confirmedEvents;
    private final Counter deferredEvents;

    @Autowired
    public RoomEventOutboxRelay(RoomEventOutboxRepository repository, RabbitTemplate rabbitTemplate,
                                MeterRegistry meterRegistry,
                                @Value("${room.events.relay.confirm-timeout-ms:3000}") long confirmTimeoutMillis) {
        this(repository, rabbitTemplate, meterRegistry, confirmTimeoutMillis, Clock.systemUTC());
    }

    RoomEventOutboxRelay(RoomEventOutboxRepository repository, RabbitTemplate rabbitTemplate, MeterRegistry meterRegistry,
                         long confirmTimeoutMillis, Clock clock) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
        this.clock = clock;
        this.confirmedEvents = Counter.builder("roomiq.room.outbox.delivery")
                .description("Room outbox events acknowledged and routed by the broker")
                .tag("outcome", "confirmed")
                .register(meterRegistry);
        this.deferredEvents = Counter.builder("roomiq.room.outbox.delivery")
                .description("Room outbox events retained for retry after delivery failure")
                .tag("outcome", "deferred")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${room.events.relay.fixed-delay-ms:1000}")
    @Transactional
    public void publishPendingEvents() {
        Instant now = clock.instant();
        for (RoomEventOutboxEntry entry : repository.claimDue(now)) {
            try {
                publish(entry);
                entry.markPublished(clock.instant());
                confirmedEvents.increment();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                defer(entry, exception, now);
                return;
            } catch (Exception exception) {
                defer(entry, exception, now);
            }
        }
    }

    private void publish(RoomEventOutboxEntry entry) throws Exception {
        String payload = entry.getPayload().getValue();
        if (payload == null || payload.isBlank()) {
            throw new IllegalStateException("Room event outbox payload is empty");
        }
        Message message = MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setMessageId(entry.getEventId().toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        CorrelationData confirmation = new CorrelationData(entry.getEventId().toString());
        rabbitTemplate.send(RoomEventMessagingConfiguration.EVENTS_EXCHANGE,
                routingKeyFor(entry.getEventType()), message, confirmation);
        CorrelationData.Confirm confirm = confirmation.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
        if (confirm == null || !confirm.ack() || confirmation.getReturned() != null) {
            throw new IllegalStateException("Room event was not confirmed and routed by the broker");
        }
    }

    private static String routingKeyFor(String eventType) {
        return switch (eventType) {
            case "RoomStatusChanged" -> RoomEventMessagingConfiguration.STATUS_ROUTING_KEY;
            case "RoomMaintenanceChanged" -> RoomEventMessagingConfiguration.MAINTENANCE_ROUTING_KEY;
            default -> throw new IllegalStateException("Unsupported Room event type in outbox");
        };
    }

    private void defer(RoomEventOutboxEntry entry, Exception exception, Instant now) {
        entry.deferUntil(now.plusSeconds(retryDelaySeconds(entry.getAttemptCount())),
                exception.getClass().getName());
        deferredEvents.increment();
        if (entry.getAttemptCount() >= OPERATIONAL_ALERT_ATTEMPT) {
            log.error("Room event repeatedly failed delivery eventId={} eventType={} attemptCount={} failureType={}",
                    entry.getEventId(), entry.getEventType(), entry.getAttemptCount(), entry.getLastError());
        } else {
            log.warn("Room event delivery deferred eventId={} eventType={} attemptCount={} failureType={}",
                    entry.getEventId(), entry.getEventType(), entry.getAttemptCount(), entry.getLastError());
        }
    }

    private static long retryDelaySeconds(int previousAttempts) {
        return Math.min(300, 1L << Math.min(previousAttempts, 9));
    }
}
