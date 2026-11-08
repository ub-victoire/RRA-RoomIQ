package rw.rra.roomiq.booking.domain.service;

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
import rw.rra.roomiq.booking.config.BookingEventMessagingConfiguration;
import rw.rra.roomiq.booking.domain.entity.BookingEventOutboxEntry;
import rw.rra.roomiq.booking.domain.repository.BookingEventOutboxRepository;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "roomiq.booking.event-relay.enabled", havingValue = "true", matchIfMissing = true)
public class BookingEventOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(BookingEventOutboxRelay.class);
    private static final int OPERATIONAL_ALERT_ATTEMPT = 10;

    private final BookingEventOutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMillis;
    private final Clock clock;
    private final Counter confirmedEvents;
    private final Counter deferredEvents;

    @Autowired
    public BookingEventOutboxRelay(BookingEventOutboxRepository outboxRepository, RabbitTemplate rabbitTemplate,
                                   MeterRegistry meterRegistry,
                                   @Value("${roomiq.booking.event-relay.confirm-timeout-ms:3000}")
                                   long confirmTimeoutMillis) {
        this(outboxRepository, rabbitTemplate, meterRegistry, confirmTimeoutMillis, Clock.systemUTC());
    }

    BookingEventOutboxRelay(BookingEventOutboxRepository outboxRepository, RabbitTemplate rabbitTemplate,
                            MeterRegistry meterRegistry, long confirmTimeoutMillis, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
        this.clock = clock;
        this.confirmedEvents = Counter.builder("roomiq.booking.outbox.delivery")
                .description("Booking outbox events acknowledged and routed by the broker")
                .tag("outcome", "confirmed")
                .register(meterRegistry);
        this.deferredEvents = Counter.builder("roomiq.booking.outbox.delivery")
                .description("Booking outbox events retained for retry after delivery failure")
                .tag("outcome", "deferred")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${roomiq.booking.event-relay.fixed-delay-ms:1000}")
    @Transactional
    public void publishPendingEvents() {
        Instant now = clock.instant();
        for (BookingEventOutboxEntry entry : outboxRepository.claimDue(now)) {
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

    private void publish(BookingEventOutboxEntry entry) throws Exception {
        String payload = entry.getPayload().getValue();
        if (payload == null) {
            throw new IllegalStateException("Booking event outbox payload is empty");
        }
        Message message = MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setMessageId(entry.getEventId().toString())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        CorrelationData confirmation = new CorrelationData(entry.getEventId().toString());
        rabbitTemplate.send(BookingEventMessagingConfiguration.EVENTS_EXCHANGE,
                routingKeyFor(entry.getEventType()), message, confirmation);
        CorrelationData.Confirm confirm = confirmation.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
        if (confirm == null || !confirm.ack() || confirmation.getReturned() != null) {
            throw new IllegalStateException("Booking event was not confirmed and routed by the broker");
        }
    }

    private static String routingKeyFor(String eventType) {
        return switch (eventType) {
            case BookingOccupancyOutboxService.EVENT_TYPE ->
                    BookingEventMessagingConfiguration.OCCUPANCY_ROUTING_KEY;
            case "BookingRequested" -> "booking.requested";
            case "BookingApproved" -> "booking.approved";
            case "BookingRejected" -> "booking.rejected";
            case "BookingCancelled" -> "booking.cancelled";
            case "ExtensionRequested" -> "booking.extension.requested";
            case "ExtensionDecided" -> "booking.extension.decided";
            case "MeetingCompleted" -> "booking.meeting.completed";
            case "MeetingReleased" -> "booking.meeting.released";
            case "AutomaticReleaseOrNoShow" -> "booking.automatic-release-or-no-show";
            case "Reminder" -> "notification.reminder";
            default -> throw new IllegalStateException("Unsupported Booking event type in outbox");
        };
    }

    private void defer(BookingEventOutboxEntry entry, Exception exception, Instant now) {
        entry.deferUntil(now.plusSeconds(retryDelaySeconds(entry.getAttemptCount())),
                exception.getClass().getName());
        deferredEvents.increment();
        if (entry.getAttemptCount() >= OPERATIONAL_ALERT_ATTEMPT) {
            log.error("Booking event repeatedly failed delivery eventId={} eventType={} attemptCount={} failureType={}",
                    entry.getEventId(), entry.getEventType(), entry.getAttemptCount(), entry.getLastError());
        } else {
            log.warn("Booking event delivery deferred eventId={} eventType={} attemptCount={} failureType={}",
                    entry.getEventId(), entry.getEventType(), entry.getAttemptCount(), entry.getLastError());
        }
    }

    private static long retryDelaySeconds(int previousAttempts) {
        return Math.min(300, 1L << Math.min(previousAttempts, 9));
    }
}
