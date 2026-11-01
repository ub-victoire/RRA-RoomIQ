package rw.rra.roomiq.notification.domain.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import rw.rra.roomiq.notification.domain.event.NotificationEvent;
import rw.rra.roomiq.notification.domain.event.NotificationEventCodec;
import rw.rra.roomiq.notification.domain.event.InvalidNotificationEventException;
import rw.rra.roomiq.notification.domain.repository.NotificationEventReceiptRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "roomiq.notification.reminders.delivery-enabled=false",
        "roomiq.notification.events.enabled=false"
})
@Testcontainers
class NotificationEventProcessingPostgresTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private NotificationEventProcessingService processingService;

    @Autowired
    private NotificationEventCodec codec;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotificationEventReceiptRepository receiptRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM notification");
        jdbcTemplate.update("DELETE FROM notification_event_receipt");
    }

    @Test
    void persistsEventReceiptAndZeroOneOrMultipleEffectsAtomically() {
        NotificationEvent zero = event(List.of());
        NotificationEvent one = event(List.of(UUID.randomUUID()));
        NotificationEvent multiple = event(List.of(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(processingService.process(zero))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED_WITHOUT_NOTIFICATIONS);
        assertThat(processingService.process(one))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED);
        assertThat(processingService.process(multiple))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED);

        assertThat(count("notification_event_receipt")).isEqualTo(3);
        assertThat(count("notification")).isEqualTo(3);
    }

    @Test
    void duplicateDeliveryDoesNotCreateAdditionalEffects() {
        NotificationEvent event = event(List.of(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(processingService.process(event))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED);
        assertThat(processingService.process(event))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.DUPLICATE);

        assertThat(count("notification_event_receipt")).isEqualTo(1);
        assertThat(count("notification")).isEqualTo(2);
    }

    @Test
    void rejectsEventIdReuseWithConflictingMetadataWithoutAdditionalEffects() {
        NotificationEvent event = event(List.of(UUID.randomUUID()));
        processingService.process(event);
        NotificationEvent conflicting = new NotificationEvent(event.eventId(), event.sourceService(),
                event.eventType(), event.eventVersion(), event.aggregateType(), event.aggregateId(),
                event.aggregateVersion() + 1, event.correlationId(), event.causationId(), event.occurredAt(),
                event.payload(), event.recipientUserIds(), event.resourceType(), event.resourceId());

        assertThatThrownBy(() -> processingService.process(conflicting))
                .isInstanceOf(InvalidNotificationEventException.class);
        assertThat(count("notification_event_receipt")).isEqualTo(1);
        assertThat(count("notification")).isEqualTo(1);
    }

    @Test
    void identityResolutionFailureRollsBackNewRequestReceiptAndEffects() {
        NotificationEvent event = scopedAdminRequestEvent();

        assertThatThrownBy(() -> processingService.process(event))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(count("notification_event_receipt")).isZero();
        assertThat(count("notification")).isZero();
    }

    @Test
    void duplicateRequestReceiptIsAcknowledgedWithoutCallingUnavailableIdentity() {
        NotificationEvent event = scopedAdminRequestEvent();
        receiptRepository.saveAndFlush(new rw.rra.roomiq.notification.domain.entity.NotificationEventReceipt(
                UUID.randomUUID(), event.eventId(), event.sourceService(), event.eventType(), event.eventVersion(),
                event.aggregateType(), event.aggregateId(), event.aggregateVersion(), event.correlationId(),
                event.causationId(), event.resourceType(), event.resourceId(), Instant.now()));

        assertThat(processingService.process(event))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.DUPLICATE);
        assertThat(count("notification_event_receipt")).isEqualTo(1);
        assertThat(count("notification")).isZero();
    }

    @Test
    void concurrentDuplicateDeliveriesCommitOnlyOneSetOfEffects() throws Exception {
        NotificationEvent event = event(List.of(UUID.randomUUID(), UUID.randomUUID()));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<NotificationEventProcessingService.ProcessingResult> first =
                    workers.submit(() -> processAtSameTime(event, ready, start));
            Future<NotificationEventProcessingService.ProcessingResult> second =
                    workers.submit(() -> processAtSameTime(event, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(NotificationEventProcessingService.ProcessingResult.PROCESSED,
                            NotificationEventProcessingService.ProcessingResult.DUPLICATE);
            assertThat(count("notification_event_receipt")).isEqualTo(1);
            assertThat(count("notification")).isEqualTo(2);
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void effectPersistenceFailureRollsBackReceiptAndCanBeRetried() {
        UUID recipient = UUID.randomUUID();
        NotificationEvent valid = event(List.of());
        NotificationEvent event = new NotificationEvent(valid.eventId(), valid.sourceService(), valid.eventType(),
                valid.eventVersion(), valid.aggregateType(), valid.aggregateId(), valid.aggregateVersion(),
                valid.correlationId(), valid.causationId(), valid.occurredAt(), valid.payload(),
                Collections.singletonList(null), valid.resourceType(), valid.resourceId());

        assertThatThrownBy(() -> processingService.process(event)).isInstanceOf(RuntimeException.class);
        assertThat(count("notification_event_receipt")).isZero();
        assertThat(count("notification")).isZero();

        NotificationEvent retry = new NotificationEvent(event.eventId(), event.sourceService(), event.eventType(),
                event.eventVersion(), event.aggregateType(), event.aggregateId(), event.aggregateVersion(),
                event.correlationId(), event.causationId(), event.occurredAt(), event.payload(),
                List.of(recipient), event.resourceType(), event.resourceId());
        assertThat(processingService.process(retry))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED);
        assertThat(count("notification_event_receipt")).isEqualTo(1);
        assertThat(count("notification")).isEqualTo(1);
    }

    @Test
    void persistedReminderSurvivesProcessorRestartAndBecomesAvailableWhenDue() {
        UUID reservationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Instant startsAt = Instant.now().plus(Duration.ofMinutes(10));
        NotificationEvent reminder = reminderEvent(UUID.randomUUID(), reservationId, recipientId, startsAt, 15);

        assertThat(processingService.process(reminder))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED);
        assertThat(countReminderRows()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM notification
                WHERE event_type = 'Reminder' AND resource_id = ?
                """, String.class, reservationId)).isEqualTo("SCHEDULED");

        NotificationEventProcessingService restartedProcessor = new NotificationEventProcessingService(
                receiptRepository, notificationRepository, entityManager);
        Integer delivered = new TransactionTemplate(transactionManager)
                .execute(status -> restartedProcessor.deliverDueReminders());
        assertThat(delivered).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM notification
                WHERE event_type = 'Reminder' AND resource_id = ?
                """, String.class, reservationId)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT scheduled_at FROM notification
                WHERE event_type = 'Reminder' AND resource_id = ?
                """, java.sql.Timestamp.class, reservationId)).isNull();
    }

    @Test
    void terminalEventBeforeReminderSchedulePreventsLateReminder() {
        UUID reservationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Instant now = Instant.now();

        NotificationEvent release = meetingReleasedEvent(reservationId, now);
        assertThat(processingService.process(release))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED_WITHOUT_NOTIFICATIONS);
        assertThat(processingService.process(release))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.DUPLICATE);
        assertThat(processingService.process(reminderEvent(UUID.randomUUID(), reservationId, recipientId,
                now.plus(Duration.ofHours(1)), 15)))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED_WITHOUT_NOTIFICATIONS);

        assertThat(countReminderRows()).isZero();
        assertThat(count("notification")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notification_event_receipt
                WHERE resource_type = 'RESERVATION' AND resource_id = ?
                """, Integer.class, reservationId)).isEqualTo(2);
    }

    @Test
    void completionAndReleaseEventsKeepReceiptsButSuppressConfiguredRecipientEffects() {
        UUID reservationId = UUID.randomUUID();
        Instant now = Instant.now();
        NotificationEvent completion = meetingCompletedEvent(reservationId, now);
        NotificationEvent release = meetingReleasedEvent(reservationId, now.plusSeconds(1));

        assertThat(processingService.process(completion))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED_WITHOUT_NOTIFICATIONS);
        assertThat(processingService.process(release))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED_WITHOUT_NOTIFICATIONS);
        assertThat(processingService.process(completion))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.DUPLICATE);
        assertThat(count("notification_event_receipt")).isEqualTo(2);
        assertThat(count("notification")).isZero();
    }

    @Test
    void terminalEventCancelsAnAlreadyScheduledReminder() {
        UUID reservationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Instant startsAt = Instant.now().plus(Duration.ofHours(1));
        processingService.process(reminderEvent(UUID.randomUUID(), reservationId, recipientId, startsAt, 15));

        processingService.process(meetingReleasedEvent(reservationId, Instant.now()));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM notification
                WHERE event_type = 'Reminder' AND resource_id = ?
                """, String.class, reservationId)).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT cancelled_at FROM notification
                WHERE event_type = 'Reminder' AND resource_id = ?
                """, java.sql.Timestamp.class, reservationId)).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT scheduled_at FROM notification
                WHERE event_type = 'Reminder' AND resource_id = ?
                """, java.sql.Timestamp.class, reservationId)).isNull();
    }

    @Test
    void terminalEventCancelsAnUnreadReminderThatBecameDue() {
        UUID reservationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        processingService.process(reminderEvent(UUID.randomUUID(), reservationId, recipientId,
                Instant.now().plus(Duration.ofMinutes(10)), 15));
        assertThat(processingService.deliverDueReminders()).isEqualTo(1);

        processingService.process(meetingReleasedEvent(reservationId, Instant.now()));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM notification
                WHERE event_type = 'Reminder' AND resource_id = ?
                """, String.class, reservationId)).isEqualTo("CANCELLED");
    }

    @Test
    void equivalentReminderEventsDoNotCreateDuplicateSchedules() {
        UUID reservationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        Instant startsAt = Instant.now().plus(Duration.ofHours(1));

        processingService.process(reminderEvent(UUID.randomUUID(), reservationId, recipientId, startsAt, 15));
        processingService.process(reminderEvent(UUID.randomUUID(), reservationId, recipientId, startsAt, 15));

        assertThat(countReminderRows()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notification_event_receipt
                WHERE resource_type = 'RESERVATION' AND resource_id = ?
                """, Integer.class, reservationId)).isEqualTo(2);
    }

    @Test
    void concurrentSchedulersMakeOneDueReminderAvailableOnce() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        processingService.process(reminderEvent(UUID.randomUUID(), reservationId, recipientId,
                Instant.now().plus(Duration.ofMinutes(10)), 15));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = workers.submit(() -> deliverAtSameTime(ready, start));
            Future<Integer> second = workers.submit(() -> deliverAtSameTime(ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM notification
                    WHERE event_type = 'Reminder' AND resource_id = ? AND status = 'PENDING'
                    """, Integer.class, reservationId)).isEqualTo(1);
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    private NotificationEventProcessingService.ProcessingResult processAtSameTime(
            NotificationEvent event, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent duplicate test did not start");
        }
        return processingService.process(event);
    }

    private int deliverAtSameTime(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent reminder delivery test did not start");
        }
        return processingService.deliverDueReminders();
    }

    private NotificationEvent reminderEvent(UUID eventId, UUID reservationId, UUID recipientId,
                                            Instant startsAt, int offsetMinutes) {
        Instant occurredAt = Instant.now();
        Instant reminderAt = startsAt.minus(Duration.ofMinutes(offsetMinutes));
        String json = """
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
                    "reminderOffsetMinutes": %d
                  }
                }
                """.formatted(eventId, UUID.randomUUID(), occurredAt, recipientId, UUID.randomUUID(),
                reservationId, startsAt, reminderAt, offsetMinutes);
        return codec.decode(json.getBytes(StandardCharsets.UTF_8));
    }

    private NotificationEvent scopedAdminRequestEvent() {
        String json = """
                {
                  "eventId": "%s",
                  "sourceService": "booking-service",
                  "eventType": "BookingRequested",
                  "eventVersion": 2,
                  "aggregateType": "BOOKING_REQUEST",
                  "aggregateId": "%s",
                  "aggregateVersion": 1,
                  "correlationId": "request-routing-correlation",
                  "causationId": null,
                  "occurredAt": "%s",
                  "payload": {
                    "recipientUserIds": [],
                    "requesterUserId": "%s",
                    "departmentId": "%s",
                    "vipRoom": false,
                    "notificationRoute": "SCOPED_ADMIN_REQUEST"
                  }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                UUID.randomUUID(), UUID.randomUUID());
        return codec.decode(json.getBytes(StandardCharsets.UTF_8));
    }

    private NotificationEvent meetingReleasedEvent(UUID reservationId, Instant releasedAt) {
        String json = """
                {
                  "eventId": "%s",
                  "sourceService": "booking-service",
                  "eventType": "MeetingReleased",
                  "eventVersion": 1,
                  "aggregateType": "MEETING",
                  "aggregateId": "%s",
                  "aggregateVersion": 1,
                  "correlationId": "release-correlation",
                  "causationId": null,
                  "occurredAt": "%s",
                  "payload": {
                    "recipientUserIds": ["%s", "%s"],
                    "meetingId": "%s",
                    "reservationId": "%s",
                    "releasedByUserId": "%s",
                    "releasedAt": "%s"
                  }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), releasedAt,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), reservationId, UUID.randomUUID(), releasedAt);
        return codec.decode(json.getBytes(StandardCharsets.UTF_8));
    }

    private NotificationEvent meetingCompletedEvent(UUID reservationId, Instant completedAt) {
        String json = """
                {
                  "eventId": "%s",
                  "sourceService": "booking-service",
                  "eventType": "MeetingCompleted",
                  "eventVersion": 1,
                  "aggregateType": "MEETING",
                  "aggregateId": "%s",
                  "aggregateVersion": 1,
                  "correlationId": "completion-correlation",
                  "causationId": null,
                  "occurredAt": "%s",
                  "payload": {
                    "recipientUserIds": ["%s", "%s"],
                    "meetingId": "%s",
                    "reservationId": "%s",
                    "completedAt": "%s"
                  }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), completedAt,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), reservationId, completedAt);
        return codec.decode(json.getBytes(StandardCharsets.UTF_8));
    }

    private int countReminderRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE event_type = 'Reminder'", Integer.class);
    }

    private NotificationEvent event(List<UUID> recipients) {
        String recipientJson = recipients.stream().map(id -> id == null ? "null" : "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String json = """
                {
                  "eventId": "%s",
                  "sourceService": "booking-service",
                  "eventType": "BookingRejected",
                  "eventVersion": 1,
                  "aggregateType": "BOOKING_REQUEST",
                  "aggregateId": "%s",
                  "aggregateVersion": 1,
                  "correlationId": "correlation-1",
                  "causationId": "causation-1",
                  "occurredAt": "2026-10-12T12:00:00Z",
                  "payload": {
                    "recipientUserIds": %s,
                    "requesterUserId": "%s",
                    "decidedByUserId": "%s",
                    "decision": "REJECTED"
                  }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), recipientJson, UUID.randomUUID(),
                UUID.randomUUID());
        return codec.decode(json.getBytes(StandardCharsets.UTF_8));
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
