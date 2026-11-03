package rw.rra.roomiq.audit.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTemplateAuditEventCodecTests {
    private final NotificationTemplateAuditEventCodec codec =
            new NotificationTemplateAuditEventCodec(new ObjectMapper());

    @Test
    void acceptsVersionedTemplateAuditEventWithoutSensitiveTemplateText() {
        UUID eventId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
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
                    "changedFields":["code","version","channel","bodyTemplate"]
                  }
                }
                """.formatted(eventId, actorId, templateId);

        var event = codec.decode(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.actorUserId()).isEqualTo(actorId);
        assertThat(event.resourceId()).isEqualTo(templateId);
        assertThat(event.sourceService()).isEqualTo("notification-service");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.metadata()).contains("\"sourceService\":\"notification-service\"")
                .contains("\"schemaVersion\":1")
                .contains("\"eventMetadata\"")
                .contains("\"code\":\"BookingApproved\"")
                .contains("\"changedFields\":[\"code\",\"version\",\"channel\",\"bodyTemplate\"]");
        assertThat(event.metadata()).doesNotContain("body text");
    }

    @Test
    void acceptsNullCorrelationAndRejectsMalformedNonNullCorrelation() {
        String nullCorrelationPayload = eventJson(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "\"correlationId\":null");

        assertThat(codec.decode(nullCorrelationPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .correlationId()).isNull();

        for (String invalidCorrelation : new String[]{"", "contains space", "contains/slash", "x".repeat(129)}) {
            String payload = eventJson(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "\"correlationId\":\"" + invalidCorrelation + "\"");
            assertThatThrownBy(() -> codec.decode(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("correlation");
        }
    }

    @Test
    void rejectsUnsupportedVersionsSensitiveFieldsAndMalformedMetadata() {
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
                  "metadata":{
                    "code":"BookingApproved",
                    "version":2,
                    "channel":"IN_APP",
                    "changedFields":["bodyTemplate"],
                    "bodyTemplate":"should never be audited"
                  }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> codec.decode(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata");
    }

    private static String eventJson(UUID eventId, UUID actorId, UUID templateId, String correlationProperty) {
        return """
                {
                  "eventId":"%s",
                  "schemaVersion":1,
                  "eventType":"NotificationTemplateCreated",
                  "timestamp":"2026-11-01T10:00:00.123456Z",
                  "sourceService":"notification-service",
                  "actorUserId":"%s",
                  "resourceType":"NOTIFICATION_TEMPLATE",
                  "resourceId":"%s",
                  %s,
                  "metadata":{
                    "code":"BookingApproved",
                    "version":2,
                    "channel":"IN_APP",
                    "changedFields":["code","version","channel","bodyTemplate"]
                  }
                }
                """.formatted(eventId, actorId, templateId, correlationProperty);
    }
}
