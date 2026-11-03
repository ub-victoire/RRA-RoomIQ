package rw.rra.roomiq.audit.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
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

    @Test
    void persistsAuditAfterTransactionAndDeduplicatesRedelivery() {
        var event = codec.decode(eventJson(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "bodyTemplate"));

        processor.process(event);
        processor.process(event);

        assertThat(repository.findById(event.eventId())).isPresent();
        AuditEvent saved = repository.findById(event.eventId()).orElseThrow();
        assertThat(saved.getAction()).isEqualTo("NotificationTemplateCreated");
        assertThat(saved.getResourceType()).isEqualTo("NOTIFICATION_TEMPLATE");
        assertThat(saved.getOutcome()).isEqualTo("SUCCESS");
        assertThat(saved.getMetadata()).doesNotContain("template contents");
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
        String payload = """
                {
                  "eventId":"%s",
                  "schemaVersion":1,
                  "eventType":"NotificationTemplateCreated",
                  "timestamp":"2026-11-01T10:00:00Z",
                  "sourceService":"notification-service",
                  "actorUserId":"%s",
                  "resourceType":"NOTIFICATION_TEMPLATE",
                  "resourceId":"%s",
                  "correlationId":"correlation-1",
                  "metadata":{
                    "code":"BookingApproved",
                    "version":2,
                    "channel":"IN_APP",
                    "changedFields":["%s"]
                  }
                }
                """.formatted(eventId, actorId, templateId, changedField);
        return payload.getBytes(StandardCharsets.UTF_8);
    }
}
