package rw.rra.roomiq.lifecycle.legalhold;

import java.time.Instant;
import java.util.UUID;

public record LegalHoldRecord(
        UUID holdId,
        long version,
        String holdReference,
        String reason,
        LegalHoldScope scope,
        LegalHoldStatus status,
        UUID initiatingActor,
        Instant createdAt,
        UUID updatedBy,
        Instant updatedAt,
        Instant effectiveFrom,
        UUID approvedBy,
        Instant approvedAt,
        UUID releaseRequestedBy,
        Instant releaseRequestedAt,
        String releaseRequestEvidenceReference,
        UUID releasedBy,
        Instant releasedAt,
        String releaseVerificationReference,
        String supportingEvidenceReference,
        String lastEvidenceReference,
        String correlationId,
        UUID lastEventId) {
}
