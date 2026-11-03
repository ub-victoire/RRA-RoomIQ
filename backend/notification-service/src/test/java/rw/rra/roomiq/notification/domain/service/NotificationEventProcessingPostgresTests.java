package rw.rra.roomiq.notification.domain.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import rw.rra.roomiq.notification.domain.dto.NotificationPreferenceUpdateRequest;
import rw.rra.roomiq.notification.domain.dto.NotificationTemplateActivationRequest;
import rw.rra.roomiq.notification.domain.dto.NotificationTemplateCreateRequest;
import rw.rra.roomiq.notification.domain.entity.DeliveryAttempt;
import rw.rra.roomiq.notification.domain.entity.Notification;
import rw.rra.roomiq.notification.domain.entity.NotificationDeliveryRedrive;
import rw.rra.roomiq.notification.domain.event.NotificationEvent;
import rw.rra.roomiq.notification.domain.event.NotificationEventCodec;
import rw.rra.roomiq.notification.domain.event.InvalidNotificationEventException;
import rw.rra.roomiq.notification.domain.repository.NotificationEventReceiptRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationAuditOutboxRepository;
import rw.rra.roomiq.notification.domain.repository.DeliveryAttemptRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationDeliveryRedriveRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationTemplateRepository;
import rw.rra.roomiq.notification.domain.repository.UserNotificationPreferenceRepository;
import rw.rra.roomiq.notification.domain.service.NotificationPreferenceService;
import rw.rra.roomiq.notification.integration.NotificationIdentityAuthenticationClient;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "roomiq.notification.reminders.delivery-enabled=false",
        "roomiq.notification.events.enabled=false",
        "roomiq.notification.audit.relay.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class NotificationEventProcessingPostgresTests {
    private static final String AUTHORIZATION = "Bearer notification-preference-test-token";

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
    private UserNotificationPreferenceRepository preferenceRepository;

    @Autowired
    private NotificationTemplateRepository templateRepository;

    @Autowired
    private NotificationTemplateRenderer templateRenderer;

    @Autowired
    private NotificationTemplateService templateService;

    @Autowired
    private NotificationAuditOutboxRepository auditOutboxRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    private NotificationDeliveryRedriveRepository deliveryRedriveRepository;

    @Autowired
    private NotificationPreferenceService preferenceService;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationIdentityAuthenticationClient identityAuthenticationClient;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM notification_delivery_redrive");
        jdbcTemplate.update("DELETE FROM delivery_attempt");
        jdbcTemplate.update("DELETE FROM notification");
        jdbcTemplate.update("DELETE FROM notification_audit_outbox");
        jdbcTemplate.update("DELETE FROM user_notification_preference");
        jdbcTemplate.update("DELETE FROM notification_event_receipt");
        jdbcTemplate.update("DELETE FROM notification_template");
        for (String eventType : codec.supportedEventTypes()) {
            jdbcTemplate.update("""
                    INSERT INTO notification_template
                        (id, code, version, channel, subject, body_template, is_active)
                    VALUES (?, ?, 1, 'IN_APP', NULL, '{{eventType}}', TRUE)
                    """, UUID.randomUUID(), eventType);
        }
    }

    @Test
    void templateVersionsSwitchAtomicallyAndAuditsExcludeTemplateContent() {
            UUID actorId = UUID.randomUUID();
            NotificationTemplateCreateRequest versionTwo = new NotificationTemplateCreateRequest(
                    "BookingApproved", 2, "IN_APP", null, "Approved: {{eventType}}");
            NotificationTemplateCreateRequest versionThree = new NotificationTemplateCreateRequest(
                    "BookingApproved", 3, "IN_APP", null, "Sensitive template content");
            var first = templateService.create(versionTwo, actorId);
            var second = templateService.create(versionThree, actorId);

            assertThat(first.active()).isFalse();
            assertThat(second.active()).isFalse();
            assertThatThrownBy(() -> templateService.create(versionTwo, actorId))
                    .isInstanceOf(rw.rra.roomiq.common.web.DomainException.class)
                    .hasMessageContaining("already exists");

            templateService.setActive(first.id(), new NotificationTemplateActivationRequest(true), actorId);
            templateService.setActive(second.id(), new NotificationTemplateActivationRequest(true), actorId);
            assertThat(templateRepository.findByCodeAndChannelAndActiveTrue("BookingApproved", "IN_APP"))
                    .get().extracting(template -> template.getVersion()).isEqualTo(3);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT is_active FROM notification_template WHERE id = ?
                    """, Boolean.class, first.id())).isFalse();

            assertThat(auditOutboxRepository.findAll()).hasSize(4);
            assertThat(auditOutboxRepository.findAll()).allSatisfy(entry -> {
                assertThat(entry.getPayload()).doesNotContain("Sensitive template content");
                assertThat(entry.getPayload()).doesNotContain("Approved: {{eventType}}");
            });
            templateService.setActive(second.id(), new NotificationTemplateActivationRequest(false), actorId);
            assertThat(templateRepository.findByCodeAndChannelAndActiveTrue("BookingApproved", "IN_APP")).isEmpty();
            assertThat(auditOutboxRepository.findAll()).hasSize(5);
    }

    @Test
    void deliveryAttemptMigrationPersistsTheApprovedAttemptFields() {
        UUID notificationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO notification
                    (id, recipient_user_id, event_type, channel, status, payload, created_at)
                VALUES (?, ?, 'BookingApproved', 'EMAIL', 'FAILED', '{}'::jsonb, ?)
                """, notificationId, userId, Timestamp.from(Instant.now()));
        Notification notification = notificationRepository.findById(notificationId).orElseThrow();

        deliveryAttemptRepository.saveAndFlush(new DeliveryAttempt(UUID.randomUUID(), notification,
                (short) 1, "FAILED", null,
                "SMTP_DELIVERY_FAILED", Instant.now()));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT result || ':' || attempt_no || ':' || failure_reason
                FROM delivery_attempt WHERE notification_id = ?
                """, String.class, notificationId)).isEqualTo("FAILED:1:SMTP_DELIVERY_FAILED");
    }

    @Test
    void approvedRedriveLedgerMigrationPersistsIdempotencyAndActorHistory() {
        UUID notificationId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(10);
        jdbcTemplate.update("""
                INSERT INTO notification
                    (id, recipient_user_id, event_type, channel, status, payload, created_at)
                VALUES (?, ?, 'BookingApproved', 'EMAIL', 'DEAD_LETTER', '{}'::jsonb, ?)
                """, notificationId, UUID.randomUUID(), Timestamp.from(createdAt));
        Notification notification = notificationRepository.findById(notificationId).orElseThrow();
        Instant requestedAt = Instant.now();

        deliveryRedriveRepository.saveAndFlush(new NotificationDeliveryRedrive(
                UUID.randomUUID(), notification, "operator-redrive-1", actorUserId, requestedAt));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT idempotency_key || ':' || actor_user_id
                FROM notification_delivery_redrive WHERE notification_id = ?
                """, String.class, notificationId))
                .isEqualTo("operator-redrive-1:" + actorUserId);
        assertThat(deliveryRedriveRepository.findFirstByNotificationIdOrderByRequestedAtDesc(notificationId))
                .isPresent();
    }

    @Test
    void scheduledEmailRetriesRemainDurableAndAreClaimedOnlyWhenDue() {
        Instant now = Instant.now();
        Notification notification = new Notification(UUID.randomUUID(), UUID.randomUUID(),
                "BookingApproved", "RESERVATION", UUID.randomUUID(), "EMAIL", "{}",
                now.minusSeconds(30), "PENDING");
        notification.scheduleEmailRetry(now.plusSeconds(60));
        notificationRepository.saveAndFlush(notification);

        Optional<Notification> beforeDue = new TransactionTemplate(transactionManager)
                .execute(status -> notificationRepository.lockNextDueEmail(now));
        Optional<Notification> whenDue = new TransactionTemplate(transactionManager)
                .execute(status -> notificationRepository.lockNextDueEmail(now.plusSeconds(60)));

        assertThat(beforeDue).isEmpty();
        assertThat(whenDue).isPresent()
                .get().extracting(Notification::getId).isEqualTo(notification.getId());
    }

    @Test
    void protectedDeliveryOperationsRedriveIdempotentlyWithoutCreatingAnotherNotification() throws Exception {
        UUID notificationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO notification
                    (id, recipient_user_id, event_type, channel, status, payload, created_at)
                VALUES (?, ?, 'BookingApproved', 'EMAIL', 'DEAD_LETTER', '{"message":"private"}'::jsonb, ?)
                """, notificationId, UUID.randomUUID(), Timestamp.from(Instant.now().minusSeconds(10)));
        when(identityAuthenticationClient.authorizeNotificationDeliveryManagement(AUTHORIZATION))
                .thenReturn(actorId);

        mockMvc.perform(post("/api/v1/internal/notification-delivery/dead-letters/{id}/redrive", notificationId)
                        .header("Authorization", AUTHORIZATION)
                        .header("Idempotency-Key", "support-ticket-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.replayed").value(false));

        mockMvc.perform(post("/api/v1/internal/notification-delivery/dead-letters/{id}/redrive", notificationId)
                        .header("Authorization", AUTHORIZATION)
                        .header("Idempotency-Key", "support-ticket-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notification WHERE id = ?
                """, Long.class, notificationId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notification_delivery_redrive WHERE notification_id = ?
                """, Long.class, notificationId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM notification WHERE id = ?
                """, String.class, notificationId)).isEqualTo("SCHEDULED");
    }

    @Test
    void notificationDeliveryHealthIsProtectedAndReturnsAggregateMetricsOnly() throws Exception {
        UUID actorId = UUID.randomUUID();
        when(identityAuthenticationClient.authorizeNotificationDeliveryManagement(AUTHORIZATION))
                .thenReturn(actorId);

        mockMvc.perform(get("/api/v1/internal/notification-delivery/health")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.emailQueueDepth").isNumber())
                .andExpect(jsonPath("$.data.deadLetterCount").isNumber())
                .andExpect(jsonPath("$.data.successfulAttempts").isNumber())
                .andExpect(jsonPath("$.data.failedAttempts").isNumber())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }

    @Test
    void openApiDocumentsProtectedDeliveryOperations() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString(
                                "/api/v1/internal/notification-delivery/dead-letters/{notificationId}/redrive")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("bearerAuth")));
    }

    @Test
    void templateAdminApiRequiresIdentityAuthorizationAndReturnsSharedEnvelope() throws Exception {
            UUID actorId = UUID.randomUUID();
            when(identityAuthenticationClient.authorizeNotificationTemplateManagement(AUTHORIZATION))
                    .thenReturn(actorId);

            mockMvc.perform(post("/api/v1/internal/notification-templates")
                            .header("Authorization", AUTHORIZATION)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"BookingRejected","version":2,"channel":"IN_APP",
                                     "bodyTemplate":"Decision: {{decision}}"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.active").value(false))
                    .andExpect(jsonPath("$.data.code").value("BookingRejected"));
    }

    @Test
    void templateMutationRollsBackWhenTransactionalAuditOutboxCannotPersist() {
        var failingOutbox = mock(NotificationAuditOutboxRepository.class);
        doThrow(new IllegalStateException("outbox unavailable"))
                    .when(failingOutbox).save(org.mockito.ArgumentMatchers.any());
        var service = new NotificationTemplateService(templateRepository, failingOutbox,
                    entityManager, new tools.jackson.databind.ObjectMapper(), Clock.systemUTC());
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    service.create(new NotificationTemplateCreateRequest(
                            "RollbackTemplate", 1, "IN_APP", null, "Body"), actorId)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("audit event could not be recorded");
        assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM notification_template WHERE code = 'RollbackTemplate'
                    """, Integer.class)).isZero();
    }

    @Test
    void concurrentTemplateActivationsLeaveExactlyOneActiveVersion() throws Exception {
        UUID actorId = UUID.randomUUID();
        var first = templateService.create(new NotificationTemplateCreateRequest(
                    "ConcurrentTemplate", 1, "IN_APP", null, "{{eventType}}"), actorId);
        var second = templateService.create(new NotificationTemplateCreateRequest(
                    "ConcurrentTemplate", 2, "IN_APP", null, "{{eventType}}"), actorId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstActivation = executor.submit(() -> activateAtSameTime(
                        first.id(), actorId, ready, start));
            Future<?> secondActivation = executor.submit(() -> activateAtSameTime(
                        second.id(), actorId, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstActivation.get(20, TimeUnit.SECONDS);
            secondActivation.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM notification_template
                    WHERE code = 'ConcurrentTemplate' AND channel = 'IN_APP' AND is_active = TRUE
                    """, Integer.class)).isEqualTo(1);
    }

    @Test
    void eventNotificationsUseActiveTemplateAndPersistOnlyEscapedRenderedFacts() throws Exception {
        UUID recipient = UUID.randomUUID();
        UUID templateId = templateRepository.findByCodeAndChannelAndActiveTrue("BookingRejected", "IN_APP")
                    .orElseThrow().getId();
        jdbcTemplate.update("UPDATE notification_template SET body_template = '<p>{{decisionComment}}</p>' WHERE id = ?",
                    templateId);
        NotificationEvent event = eventWithDecisionComment(recipient, "<script>alert('x')</script>");

        assertThat(processingService.process(event))
                    .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED);
        assertThat(jdbcTemplate.queryForObject("""
                    SELECT notification_template_id FROM notification WHERE recipient_user_id = ?
                    """, UUID.class, recipient)).isEqualTo(templateId);
        assertThat(jdbcTemplate.queryForObject("""
                    SELECT payload ->> 'message' FROM notification WHERE recipient_user_id = ?
                    """, String.class, recipient)).isEqualTo(
                    "<p>&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;</p>");
    }

    @Test
    void notificationProcessingFailsAndRollsBackWhenActiveTemplateIsMissing() {
            UUID recipient = UUID.randomUUID();
            NotificationEvent event = event(List.of(recipient));
            jdbcTemplate.update("DELETE FROM notification_template WHERE code = 'BookingRejected'");

            assertThatThrownBy(() -> processingService.process(event))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Active IN_APP template is unavailable");
            assertThat(receiptRepository.findByEventId(event.eventId())).isEmpty();
            assertThat(count("notification")).isZero();
    }

    private void activateAtSameTime(UUID templateId, UUID actorId, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent template activation test did not start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent template activation test was interrupted", exception);
        }
        templateService.setActive(templateId, new NotificationTemplateActivationRequest(true), actorId);
    }


    @Test
    void preferenceApiReturnsEffectiveDefaultsForAuthenticatedIdentityUser() throws Exception {
        UUID userId = UUID.randomUUID();
        when(identityAuthenticationClient.authenticate(AUTHORIZATION)).thenReturn(userId);

        mockMvc.perform(get("/api/v1/me/notification-preferences")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(codec.supportedEventTypes().size()))
                .andExpect(jsonPath("$.data[0].inAppEnabled").value(true))
                .andExpect(jsonPath("$.data[0].emailEnabled").value(false))
                .andExpect(jsonPath("$.data[0].configured").value(false));
    }

    @Test
    void preferenceUpdatesAreOwnerScopedAndIdempotentWhenRepeated() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        when(identityAuthenticationClient.authenticate(AUTHORIZATION)).thenReturn(owner, owner, otherUser);

        String request = """
                {"inAppEnabled":false,"emailEnabled":true}
                """;
        mockMvc.perform(put("/api/v1/me/notification-preferences/BookingApproved")
                        .header("Authorization", AUTHORIZATION)
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.inAppEnabled").value(false))
                .andExpect(jsonPath("$.data.emailEnabled").value(true))
                .andExpect(jsonPath("$.data.configured").value(true));

        UUID savedId = preferenceRepository.findByUserIdAndEventType(owner, "BookingApproved")
                .orElseThrow().getId();
        mockMvc.perform(put("/api/v1/me/notification-preferences/BookingApproved")
                        .header("Authorization", AUTHORIZATION)
                        .contentType("application/json")
                        .content(request))
                .andExpect(status().isOk());

        var otherUserView = preferenceService.getPreferences(AUTHORIZATION).stream()
                .filter(item -> item.eventType().equals("BookingApproved"))
                .findFirst().orElseThrow();
        assertThat(otherUserView.configured()).isFalse();
        assertThat(otherUserView.inAppEnabled()).isTrue();
        assertThat(otherUserView.emailEnabled()).isFalse();
        assertThat(preferenceRepository.findByUserIdAndEventType(owner, "BookingApproved")
                .orElseThrow().getId()).isEqualTo(savedId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM user_notification_preference
                WHERE user_id = ? AND event_type = 'BookingApproved'
                """, Integer.class, owner)).isEqualTo(1);
    }

    @Test
    void preferenceValidationRejectsUnknownEventsMissingFlagsAndInvalidChannels() {
        UUID userId = UUID.randomUUID();
        when(identityAuthenticationClient.authenticate(AUTHORIZATION)).thenReturn(userId);

        assertThatThrownBy(() -> preferenceService.updatePreference(AUTHORIZATION, "UnrecognizedEvent",
                new NotificationPreferenceUpdateRequest(true, false)))
                .isInstanceOf(rw.rra.roomiq.common.web.DomainException.class);
        assertThatThrownBy(() -> preferenceService.updatePreference(AUTHORIZATION, "BookingApproved",
                new NotificationPreferenceUpdateRequest(null, false)))
                .isInstanceOf(rw.rra.roomiq.common.web.DomainException.class);
        assertThatThrownBy(() -> preferenceService.isChannelEnabled(userId, "BookingApproved", "SMS"))
                .isInstanceOf(rw.rra.roomiq.common.web.DomainException.class);
        assertThat(count("user_notification_preference")).isZero();
    }

    @Test
    void preferenceApiReturnsSharedValidationErrorForUnsupportedEventAndMissingFlags() throws Exception {
        when(identityAuthenticationClient.authenticate(AUTHORIZATION)).thenReturn(UUID.randomUUID(),
                UUID.randomUUID());

        mockMvc.perform(put("/api/v1/me/notification-preferences/UnsupportedEvent")
                        .header("Authorization", AUTHORIZATION)
                        .contentType("application/json")
                        .content("""
                                {"inAppEnabled":true,"emailEnabled":false}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION_PREFERENCE"));

        mockMvc.perform(put("/api/v1/me/notification-preferences/BookingApproved")
                        .header("Authorization", AUTHORIZATION)
                        .contentType("application/json")
                        .content("""
                                {"inAppEnabled":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION_PREFERENCE"));
        assertThat(count("user_notification_preference")).isZero();
    }

    @Test
    void savedChannelFlagsAreIndependentAndMissingEmailPreferenceIsDisabled() {
        UUID userId = UUID.randomUUID();
        when(identityAuthenticationClient.authenticate(AUTHORIZATION)).thenReturn(userId);
        assertThat(preferenceService.isChannelEnabled(userId, "BookingApproved", "IN_APP")).isTrue();
        assertThat(preferenceService.isChannelEnabled(userId, "BookingApproved", "EMAIL")).isFalse();

        preferenceService.updatePreference(AUTHORIZATION, "BookingApproved",
                new NotificationPreferenceUpdateRequest(false, true));

        assertThat(preferenceService.isChannelEnabled(userId, "BookingApproved", "IN_APP")).isFalse();
        assertThat(preferenceService.isChannelEnabled(userId, "BookingApproved", "EMAIL")).isTrue();
    }

    @Test
    void channelPreferencesCreateOnlyEnabledEmailEffects() {
        UUID recipient = UUID.randomUUID();
        when(identityAuthenticationClient.authenticate(AUTHORIZATION)).thenReturn(recipient, recipient);
        seedEmailTemplate("BookingRejected");
        preferenceService.updatePreference(AUTHORIZATION, "BookingRejected",
                new NotificationPreferenceUpdateRequest(false, true));

        assertThat(processingService.process(event(List.of(recipient))))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED);
        assertThat(jdbcTemplate.queryForObject("SELECT channel FROM notification", String.class))
                .isEqualTo("EMAIL");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM notification", String.class))
                .isEqualTo("PENDING");

        UUID reservationId = UUID.randomUUID();
        seedEmailTemplate("Reminder");
        NotificationEvent reminder = reminderEvent(UUID.randomUUID(), reservationId, recipient,
                Instant.now().plus(Duration.ofHours(1)), 15);
        preferenceService.updatePreference(AUTHORIZATION, "Reminder",
                new NotificationPreferenceUpdateRequest(false, true));
        assertThat(processingService.process(reminder))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT channel FROM notification WHERE event_type = 'Reminder'
                """, String.class)).isEqualTo("EMAIL");
    }

    @Test
    void disablingReminderPreferenceBeforeDeliveryCancelsTheScheduledNotification() {
        UUID recipient = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        seedEmailTemplate("Reminder");
        when(identityAuthenticationClient.authenticate(AUTHORIZATION)).thenReturn(recipient);
        preferenceService.updatePreference(AUTHORIZATION, "Reminder",
                new NotificationPreferenceUpdateRequest(true, true));
        NotificationEvent reminder = reminderEvent(UUID.randomUUID(), reservationId, recipient,
                Instant.now().plus(Duration.ofMinutes(10)), 15);
        assertThat(processingService.process(reminder))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED);

        preferenceService.updatePreference(AUTHORIZATION, "Reminder",
                new NotificationPreferenceUpdateRequest(false, false));

        assertThat(processingService.deliverDueReminders()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM notification
                WHERE event_type = 'Reminder' AND resource_id = ? AND channel = 'IN_APP'
                """, String.class, reservationId)).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notification
                WHERE event_type = 'Reminder' AND resource_id = ? AND status = 'CANCELLED'
                """, Integer.class, reservationId)).isEqualTo(2);
    }

    @Test
    void enabledInAppPreferenceCreatesInAppEffectWhenEmailIsDisabled() {
        UUID recipient = UUID.randomUUID();
        when(identityAuthenticationClient.authenticate(AUTHORIZATION)).thenReturn(recipient);
        preferenceService.updatePreference(AUTHORIZATION, "BookingRejected",
                new NotificationPreferenceUpdateRequest(true, false));

        assertThat(processingService.process(event(List.of(recipient))))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED);
        assertThat(count("notification")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT channel FROM notification", String.class))
                .isEqualTo("IN_APP");
    }

    private void seedEmailTemplate(String eventType) {
        jdbcTemplate.update("""
                INSERT INTO notification_template
                    (id, code, version, channel, subject, body_template, is_active)
                VALUES (?, ?, 1, 'EMAIL', 'RoomIQ notification', '<p>{{eventType}}</p>', TRUE)
                """, UUID.randomUUID(), eventType);
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
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload ->> 'reminderOffsetMinutes' FROM notification
                WHERE event_type = 'Reminder' AND resource_id = ?
                """, String.class, reservationId)).isEqualTo("15");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload ->> 'reservationId' FROM notification
                WHERE event_type = 'Reminder' AND resource_id = ?
                """, String.class, reservationId)).isEqualTo(reservationId.toString());

        NotificationEventProcessingService restartedProcessor = new NotificationEventProcessingService(
                receiptRepository, notificationRepository, entityManager, java.time.Clock.systemUTC(),
                null, preferenceService, templateRepository, templateRenderer, new tools.jackson.databind.ObjectMapper());
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

    private NotificationEvent eventWithDecisionComment(UUID recipient, String decisionComment) {
        String json = """
                {
                  "eventId": "%s",
                  "sourceService": "booking-service",
                  "eventType": "BookingRejected",
                  "eventVersion": 1,
                  "aggregateType": "BOOKING_REQUEST",
                  "aggregateId": "%s",
                  "aggregateVersion": 1,
                  "correlationId": "correlation-template",
                  "causationId": null,
                  "occurredAt": "2026-10-12T12:00:00Z",
                  "payload": {
                    "recipientUserIds": ["%s"],
                    "requesterUserId": "%s",
                    "decidedByUserId": "%s",
                    "decision": "REJECTED",
                    "decisionComment": "%s"
                  }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), recipient, recipient,
                UUID.randomUUID(), decisionComment);
        return codec.decode(json.getBytes(StandardCharsets.UTF_8));
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
