package rw.rra.roomiq.audit.domain;

import java.time.Instant;
import java.util.UUID;

public record NotificationTemplateAuditEvent(
        UUID eventId,
        int schemaVersion,
        String eventType,
        Instant timestamp,
        String sourceService,
        UUID actorUserId,
        String resourceType,
        UUID resourceId,
        String correlationId,
        String metadata
) {
}
