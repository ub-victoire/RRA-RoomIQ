package rw.rra.roomiq.audit.domain;

import java.time.Instant;
import java.util.UUID;

public record IdentityAuditEvent(
        UUID eventId,
        int schemaVersion,
        String eventType,
        Instant timestamp,
        UUID actorUserId,
        String resourceType,
        UUID resourceId,
        UUID scopeOfficeBuildingId,
        String outcome,
        String correlationId,
        String metadata
) {
}
