package rw.rra.roomiq.audit.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "roomiq.auth.jwt-secret=test-only-signing-key-at-least-32-bytes-long",
        "roomiq.audit.notification.events.enabled=false",
        "roomiq.audit.identity.events.enabled=false"
})
@Testcontainers
class NotificationTemplateAuditPersistencePostgresTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("audit-test-roles.sql");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "roomiq_audit_app");
        registry.add("spring.datasource.password", () -> "audit-app-test-password");
        registry.add("spring.flyway.user", () -> "roomiq_audit_migrator");
        registry.add("spring.flyway.password", () -> "audit-migrator-test-password");
    }

    @Autowired
    private NotificationTemplateAuditEventCodec codec;

    @Autowired
    private NotificationTemplateAuditEventProcessor processor;

    @Autowired
    private AuditEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsAuditAfterTransactionAndDeduplicatesRedelivery() {
        UUID actorId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        var event = codec.decode(eventJson(UUID.randomUUID(), actorId, templateId, "bodyTemplate"));

        processor.process(event);
        processor.process(event);

        AuditEvent saved = repository.findById(event.eventId()).orElseThrow();
        assertThat(saved.getAction()).isEqualTo("NotificationTemplateCreated");
        assertThat(saved.getResourceType()).isEqualTo("NOTIFICATION_TEMPLATE");
        assertThat(saved.getActorUserId()).isEqualTo(actorId);
        assertThat(saved.getResourceId()).isEqualTo(templateId);
        assertThat(saved.getScopeOfficeBuildingId()).isNull();
        assertThat(saved.getOutcome()).isEqualTo("SUCCESS");
        assertThat(saved.getCorrelationId()).isEqualTo("correlation-1");
        assertThat(saved.getOccurredAt()).isEqualTo(Instant.parse("2026-11-01T10:00:00.123456Z"));
        assertThat(saved.getMetadata())
                .contains("\"sourceService\": \"notification-service\"")
                .contains("\"schemaVersion\": 1")
                .contains("\"eventMetadata\"")
                .contains("\"code\": \"BookingApproved\"")
                .contains("\"changedFields\": [")
                .doesNotContain("template contents", "body text");
    }

    @Test
    void persistsMissingCorrelationAsNull() {
        var event = codec.decode(eventJson(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "bodyTemplate", null, "2026-11-01T10:00:00Z"));

        processor.process(event);

        assertThat(repository.findById(event.eventId()).orElseThrow().getCorrelationId()).isNull();
    }

    @Test
    void concurrentDuplicateDeliveriesPersistOneRecord() {
        var event = codec.decode(eventJson(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "bodyTemplate"));
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = CompletableFuture.runAsync(() -> processAfter(start, event), executor);
            var second = CompletableFuture.runAsync(() -> processAfter(start, event), executor);
            start.countDown();
            CompletableFuture.allOf(first, second).join();
        }

        assertThat(repository.findById(event.eventId())).isPresent();
        assertThat(jdbcCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void rejectsConflictingDuplicateEventInsteadOfSilentlyOverwritingAudit() {
        UUID eventId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        var original = codec.decode(eventJson(eventId, actorId, templateId, "bodyTemplate"));
        var conflicting = codec.decode(eventJson(eventId, actorId, templateId, "subject"));

        processor.process(original);
        assertThatThrownBy(() -> processor.process(conflicting))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reused with different content");
        assertThat(repository.findById(eventId)).isPresent();
    }

    private static byte[] eventJson(UUID eventId, UUID actorId, UUID templateId, String changedField) {
        return eventJson(eventId, actorId, templateId, changedField,
                "\"correlationId\":\"correlation-1\"", "2026-11-01T10:00:00.123456Z");
    }

    private static byte[] eventJson(UUID eventId, UUID actorId, UUID templateId, String changedField,
                                    String correlationProperty, String timestamp) {
        String correlation = correlationProperty == null ? "\"correlationId\":null" : correlationProperty;
        String payload = """
                {
                  "eventId":"%s",
                  "schemaVersion":1,
                  "eventType":"NotificationTemplateCreated",
                  "timestamp":"%s",
                  "sourceService":"notification-service",
                  "actorUserId":"%s",
                  "resourceType":"NOTIFICATION_TEMPLATE",
                  "resourceId":"%s",
                  %s,
                  "metadata":{
                    "code":"BookingApproved",
                    "version":2,
                    "channel":"IN_APP",
                    "changedFields":["%s"]
                  }
                }
                """.formatted(eventId, timestamp, actorId, templateId, correlation, changedField);
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    private void processAfter(CountDownLatch start, NotificationTemplateAuditEvent event) {
        try {
            start.await();
            processor.process(event);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent audit test was interrupted", exception);
        }
    }

    private int jdbcCount(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_event WHERE id = ?",
                Integer.class, eventId);
    }
}
