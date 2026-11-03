package rw.rra.roomiq.audit.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "AuditEventSearchItem", description = "Public Audit event search result. Metadata is never included.")
public record AuditEventSearchItemResponse(
        UUID id,
        UUID actorUserId,
        String action,
        String resourceType,
        UUID resourceId,
        UUID buildingId,
        String outcome,
        String correlationId,
        Instant occurredAt) {
}
