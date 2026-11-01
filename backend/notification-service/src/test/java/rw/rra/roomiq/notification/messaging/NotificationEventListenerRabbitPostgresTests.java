package rw.rra.roomiq.notification.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "spring.rabbitmq.listener.simple.retry.enabled=true",
        "spring.rabbitmq.listener.simple.retry.max-attempts=3",
        "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
        "spring.rabbitmq.listener.simple.retry.multiplier=2",
        "spring.rabbitmq.listener.simple.retry.max-interval=100ms",
        "roomiq.notification.reminders.delivery-enabled=false",
        "roomiq.notification.events.enabled=true"
})
@Testcontainers
class NotificationEventListenerRabbitPostgresTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void configureDependencies(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearState() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS notification_fail_once_trigger ON notification");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS notification_fail_once()");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS notification_fail_once_sequence");
        jdbcTemplate.update("DELETE FROM notification");
        jdbcTemplate.update("DELETE FROM notification_event_receipt");
        rabbitAdmin.purgeQueue(NotificationEventMessagingConfiguration.QUEUE, false);
        rabbitAdmin.purgeQueue(NotificationEventMessagingConfiguration.DEAD_LETTER_QUEUE, false);
    }

    @Test
    void commitsEffectsBeforeAutoAcknowledgementAndDeduplicatesRedelivery() {
        UUID eventId = UUID.randomUUID();
        String body = bookingRejected(eventId);

        publish("booking.rejected", body);
        await(() -> count("notification_event_receipt") == 1 && count("notification") == 1);
        publish("booking.rejected", body);
        await(() -> queueCount(NotificationEventMessagingConfiguration.QUEUE) == 0);

        assertThat(count("notification_event_receipt")).isEqualTo(1);
        assertThat(count("notification")).isEqualTo(1);
        assertThat(queueCount(NotificationEventMessagingConfiguration.QUEUE)).isZero();
        assertThat(queueCount(NotificationEventMessagingConfiguration.DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void roomStatusRedeliveryCreatesOnlyOneNotificationAndReceipt() {
        UUID eventId = UUID.randomUUID();
        String body = roomStatusChanged(eventId);

        publish("room.status.changed", body);
        await(() -> count("notification_event_receipt") == 1 && count("notification") == 1);
        publish("room.status.changed", body);
        await(() -> queueCount(NotificationEventMessagingConfiguration.QUEUE) == 0);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_event_receipt WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
        assertThat(count("notification")).isEqualTo(1);
        assertThat(queueCount(NotificationEventMessagingConfiguration.DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void reminderEventIsAcknowledgedOnlyAfterItsDurableScheduleIsSaved() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        String body = reminder(eventId, reservationId);

        publish("notification.reminder", body);
        await(() -> count("notification_event_receipt") == 1 && count("notification") == 1
                && "SCHEDULED".equals(jdbcTemplate.queryForObject("""
                        SELECT status FROM notification WHERE resource_id = ?
                        """, String.class, reservationId)));
        publish("notification.reminder", body);
        await(() -> queueCount(NotificationEventMessagingConfiguration.QUEUE) == 0);

        assertThat(count("notification_event_receipt")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notification
                WHERE event_type = 'Reminder' AND resource_id = ? AND status = 'SCHEDULED'
                """, Integer.class, reservationId)).isEqualTo(1);
        assertThat(queueCount(NotificationEventMessagingConfiguration.DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void malformedAndUnsupportedMessagesAreDeadLetteredWhileValidEventsContinue() {
        publish("booking.requested", "{malformed");
        publish("booking.requested", bookingRequested(UUID.randomUUID(), 3));
        UUID validEventId = UUID.randomUUID();
        publish("booking.requested", bookingRequested(validEventId, 1));

        await(() -> count("notification_event_receipt") == 1
                && queueCount(NotificationEventMessagingConfiguration.DEAD_LETTER_QUEUE) == 2);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_event_receipt WHERE event_id = ?",
                Integer.class, validEventId)).isEqualTo(1);
        assertThat(count("notification")).isZero();
        assertThat(queueCount(NotificationEventMessagingConfiguration.DEAD_LETTER_QUEUE)).isEqualTo(2);
    }

    @Test
    void transientDatabaseFailureRollsBackThenRetriesSuccessfully() {
        jdbcTemplate.execute("CREATE SEQUENCE notification_fail_once_sequence START WITH 1");
        jdbcTemplate.execute("""
                CREATE FUNCTION notification_fail_once() RETURNS trigger AS $$
                BEGIN
                    IF nextval('notification_fail_once_sequence') = 1 THEN
                        RAISE EXCEPTION 'temporary notification persistence failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER notification_fail_once_trigger
                BEFORE INSERT ON notification
                FOR EACH ROW EXECUTE FUNCTION notification_fail_once()
                """);
        UUID eventId = UUID.randomUUID();

        publish("booking.rejected", bookingRejected(eventId));
        await(() -> count("notification_event_receipt") == 1 && count("notification") == 1);

        assertThat(count("notification_event_receipt")).isEqualTo(1);
        assertThat(count("notification")).isEqualTo(1);
        assertThat(queueCount(NotificationEventMessagingConfiguration.DEAD_LETTER_QUEUE)).isZero();
    }

    private void publish(String routingKey, String body) {
        Message message = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        rabbitTemplate.send(NotificationEventMessagingConfiguration.EVENTS_EXCHANGE, routingKey, message);
    }

    private String bookingRequested(UUID eventId, int version) {
        return """
                {
                  "eventId": "%s",
                  "sourceService": "booking-service",
                  "eventType": "BookingRequested",
                  "eventVersion": %d,
                  "aggregateType": "BOOKING_REQUEST",
                  "aggregateId": "%s",
                  "aggregateVersion": 1,
                  "correlationId": "correlation-rabbit",
                  "causationId": "causation-rabbit",
                  "occurredAt": "2026-10-12T12:00:00Z",
                  "payload": {
                    "recipientUserIds": ["%s"],
                    "requesterUserId": "%s"
                  }
                }
                """.formatted(eventId, version, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private String bookingRejected(UUID eventId) {
        return """
                {
                  "eventId": "%s",
                  "sourceService": "booking-service",
                  "eventType": "BookingRejected",
                  "eventVersion": 1,
                  "aggregateType": "BOOKING_REQUEST",
                  "aggregateId": "%s",
                  "aggregateVersion": 1,
                  "correlationId": "correlation-rabbit",
                  "causationId": "causation-rabbit",
                  "occurredAt": "2026-10-12T12:00:00Z",
                  "payload": {
                    "recipientUserIds": ["%s"],
                    "requesterUserId": "%s",
                    "decidedByUserId": "%s",
                    "decision": "REJECTED"
                  }
                }
                """.formatted(eventId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
    }

    private String roomStatusChanged(UUID eventId) {
        UUID roomId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        return """
                {
                  "eventId": "%s",
                  "sourceService": "room-service",
                  "eventType": "RoomStatusChanged",
                  "eventVersion": 1,
                  "aggregateType": "ROOM",
                  "aggregateId": "%s",
                  "aggregateVersion": 3,
                  "correlationId": "room-status-correlation",
                  "causationId": null,
                  "occurredAt": "2026-10-12T12:00:00Z",
                  "payload": {
                    "recipientUserIds": ["%s"],
                    "roomId": "%s",
                    "status": "MAINTENANCE"
                  }
                }
                """.formatted(eventId, roomId, recipientId, roomId);
    }

    private String reminder(UUID eventId, UUID reservationId) {
        UUID meetingId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        Instant startsAt = occurredAt.plus(Duration.ofHours(1));
        Instant reminderAt = startsAt.minus(Duration.ofMinutes(15));
        return """
                {
                  "eventId": "%s",
                  "sourceService": "booking-service",
                  "eventType": "Reminder",
                  "eventVersion": 1,
                  "aggregateType": "MEETING",
                  "aggregateId": "%s",
                  "aggregateVersion": 1,
                  "correlationId": "reminder-correlation",
                  "causationId": null,
                  "occurredAt": "%s",
                  "payload": {
                    "recipientUserIds": ["%s"],
                    "meetingId": "%s",
                    "reservationId": "%s",
                    "occurrenceStartsAt": "%s",
                    "timezone": "Africa/Kigali",
                    "reminderAt": "%s",
                    "reminderOffsetMinutes": 15
                  }
                }
                """.formatted(eventId, meetingId, occurredAt, recipientId, meetingId,
                reservationId, startsAt, reminderAt);
    }

    private long queueCount(String queue) {
        return rabbitAdmin.getQueueInfo(queue).getMessageCount();
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for RabbitMQ event processing", exception);
            }
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
