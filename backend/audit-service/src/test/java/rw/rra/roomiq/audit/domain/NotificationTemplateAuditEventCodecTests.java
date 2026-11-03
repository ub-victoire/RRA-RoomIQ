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
        assertThat(event.metadata()).doesNotContain("body text");
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
}
