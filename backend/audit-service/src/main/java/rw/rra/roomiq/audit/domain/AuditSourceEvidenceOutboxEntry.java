package rw.rra.roomiq.audit.domain;

import java.time.Instant;
import java.util.UUID;

public record AuditSourceEvidenceOutboxEntry(
        UUID auditEventId,
        String action,
        String categoryCode,
        String startBasisCode,
        Instant recordCreatedAt,
        Instant occurredAt,
        Instant observedAt,
        String correlationId,
        int attemptCount) {
}
