package rw.rra.roomiq.booking.domain.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PGobject;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import rw.rra.roomiq.booking.config.BookingEventMessagingConfiguration;
import rw.rra.roomiq.booking.domain.entity.BookingEventOutboxEntry;
import rw.rra.roomiq.booking.domain.repository.BookingEventOutboxRepository;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingEventOutboxRelayTests {
    private static final Instant NOW = Instant.parse("2030-04-10T09:00:00Z");

    @Mock
    private BookingEventOutboxRepository outboxRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private SimpleMeterRegistry meterRegistry;
    private BookingEventOutboxRelay relay;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        relay = new BookingEventOutboxRelay(outboxRepository, rabbitTemplate, meterRegistry,
                100, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void publishesPersistentVersionedJsonWithStableEventIdAndMarksPublished() {
        BookingEventOutboxEntry entry = entry(UUID.randomUUID());
        when(outboxRepository.claimDue(NOW)).thenReturn(List.of(entry));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, "ack"));
            return null;
        }).when(rabbitTemplate).send(eq(BookingEventMessagingConfiguration.EVENTS_EXCHANGE),
                eq(BookingEventMessagingConfiguration.OCCUPANCY_ROUTING_KEY), any(Message.class),
                any(CorrelationData.class));

        relay.publishPendingEvents();

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).send(eq(BookingEventMessagingConfiguration.EVENTS_EXCHANGE),
                eq(BookingEventMessagingConfiguration.OCCUPANCY_ROUTING_KEY), messageCaptor.capture(),
                correlationCaptor.capture());
        Message message = messageCaptor.getValue();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(entry.getPayload().getValue());
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(entry.getEventId().toString());
        assertThat(correlationCaptor.getValue().getId()).isEqualTo(entry.getEventId().toString());
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(entry.getPublishedAt()).isEqualTo(NOW);
        assertThat(entry.getAttemptCount()).isZero();
        assertThat(deliveryCount("confirmed")).isEqualTo(1);
        assertThat(deliveryCount("deferred")).isZero();
    }

    @Test
    void nackDefersTheSameDurableEventWithAttemptAndFailureMetadata() {
        BookingEventOutboxEntry entry = entry(UUID.randomUUID());
        when(outboxRepository.claimDue(NOW)).thenReturn(List.of(entry));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbitTemplate).send(eq(BookingEventMessagingConfiguration.EVENTS_EXCHANGE),
                eq(BookingEventMessagingConfiguration.OCCUPANCY_ROUTING_KEY), any(Message.class),
                any(CorrelationData.class));

        relay.publishPendingEvents();

        assertThat(entry.getPublishedAt()).isNull();
        assertThat(entry.getAttemptCount()).isEqualTo(1);
        assertThat(entry.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(entry.getLastError()).isEqualTo(IllegalStateException.class.getName());
        assertThat(deliveryCount("deferred")).isEqualTo(1);
    }

    @Test
    void brokerExceptionRetainsTheEventAndDefersRetry() {
        BookingEventOutboxEntry entry = entry(UUID.randomUUID());
        when(outboxRepository.claimDue(NOW)).thenReturn(List.of(entry));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(rabbitTemplate).send(any(String.class), any(String.class), any(Message.class),
                        any(CorrelationData.class));

        relay.publishPendingEvents();

        assertThat(entry.getPublishedAt()).isNull();
        assertThat(entry.getAttemptCount()).isEqualTo(1);
        assertThat(entry.getLastError()).isEqualTo(IllegalStateException.class.getName());
        assertThat(entry.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(deliveryCount("deferred")).isEqualTo(1);
    }

    @Test
    void brokerReturnDefersEvenWhenPublisherConfirmIsPositive() {
        BookingEventOutboxEntry entry = entry(UUID.randomUUID());
        when(outboxRepository.claimDue(NOW)).thenReturn(List.of(entry));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(invocation.getArgument(2), 312, "NO_ROUTE",
                    BookingEventMessagingConfiguration.EVENTS_EXCHANGE,
                    BookingEventMessagingConfiguration.OCCUPANCY_ROUTING_KEY));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, "ack"));
            return null;
        }).when(rabbitTemplate).send(eq(BookingEventMessagingConfiguration.EVENTS_EXCHANGE),
                eq(BookingEventMessagingConfiguration.OCCUPANCY_ROUTING_KEY), any(Message.class),
                any(CorrelationData.class));

        relay.publishPendingEvents();

        assertThat(entry.getPublishedAt()).isNull();
        assertThat(entry.getAttemptCount()).isEqualTo(1);
        assertThat(entry.getLastError()).isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void interruptedConfirmWaitDefersAndRestoresThreadInterrupt() {
        BookingEventOutboxEntry entry = entry(UUID.randomUUID());
        when(outboxRepository.claimDue(NOW)).thenReturn(List.of(entry));
        Thread.currentThread().interrupt();
        try {
            relay.publishPendingEvents();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(entry.getPublishedAt()).isNull();
            assertThat(entry.getAttemptCount()).isEqualTo(1);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void redeliveryAfterUncommittedBrokerConfirmationReusesTheStableEventId() {
        BookingEventOutboxEntry entry = entry(UUID.randomUUID());
        when(outboxRepository.claimDue(NOW)).thenReturn(List.of(entry));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, "ack"));
            return null;
        }).when(rabbitTemplate).send(eq(BookingEventMessagingConfiguration.EVENTS_EXCHANGE),
                eq(BookingEventMessagingConfiguration.OCCUPANCY_ROUTING_KEY), any(Message.class),
                any(CorrelationData.class));

        relay.publishPendingEvents();
        entry.markPublished(null);
        relay.publishPendingEvents();

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate, org.mockito.Mockito.times(2)).send(
                eq(BookingEventMessagingConfiguration.EVENTS_EXCHANGE),
                eq(BookingEventMessagingConfiguration.OCCUPANCY_ROUTING_KEY), messageCaptor.capture(),
                any(CorrelationData.class));
        assertThat(messageCaptor.getAllValues()).extracting(
                message -> message.getMessageProperties().getMessageId())
                .containsExactly(entry.getEventId().toString(), entry.getEventId().toString());
    }

    @Test
    void routesBusinessEventsToTheirVersionedNotificationRoutingKeys() {
        UUID eventId = UUID.randomUUID();
        BookingEventOutboxEntry entry = entry(eventId, "BookingRequested");
        when(outboxRepository.claimDue(NOW)).thenReturn(List.of(entry));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, "ack"));
            return null;
        }).when(rabbitTemplate).send(eq(BookingEventMessagingConfiguration.EVENTS_EXCHANGE),
                eq("booking.requested"), any(Message.class), any(CorrelationData.class));

        relay.publishPendingEvents();

        verify(rabbitTemplate).send(eq(BookingEventMessagingConfiguration.EVENTS_EXCHANGE),
                eq("booking.requested"), any(Message.class), any(CorrelationData.class));
        assertThat(entry.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void routesReminderEventsToNotificationConsumers() {
        UUID eventId = UUID.randomUUID();
        BookingEventOutboxEntry entry = entry(eventId, "Reminder");
        when(outboxRepository.claimDue(NOW)).thenReturn(List.of(entry));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, "ack"));
            return null;
        }).when(rabbitTemplate).send(eq(BookingEventMessagingConfiguration.EVENTS_EXCHANGE),
                eq("notification.reminder"), any(Message.class), any(CorrelationData.class));

        relay.publishPendingEvents();

        verify(rabbitTemplate).send(eq(BookingEventMessagingConfiguration.EVENTS_EXCHANGE),
                eq("notification.reminder"), any(Message.class), any(CorrelationData.class));
        assertThat(entry.getPublishedAt()).isEqualTo(NOW);
    }

    private static BookingEventOutboxEntry entry(UUID eventId) {
        return entry(eventId, BookingOccupancyOutboxService.EVENT_TYPE);
    }

    private static BookingEventOutboxEntry entry(UUID eventId, String eventType) {
        PGobject payload = new PGobject();
        payload.setType("jsonb");
        try {
            payload.setValue("{\"eventId\":\"" + eventId + "\",\"eventVersion\":1}");
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
        return new BookingEventOutboxEntry(eventId, eventType, 1,
                BookingOccupancyOutboxService.AGGREGATE_TYPE, UUID.randomUUID(), 1, payload, NOW, NOW);
    }

    private double deliveryCount(String outcome) {
        return meterRegistry.get("roomiq.booking.outbox.delivery")
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
