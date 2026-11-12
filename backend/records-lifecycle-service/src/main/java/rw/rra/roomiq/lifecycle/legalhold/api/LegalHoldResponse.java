package rw.rra.roomiq.lifecycle.legalhold.api;

import rw.rra.roomiq.lifecycle.legalhold.LegalHoldRecord;

import java.time.Instant;
import java.util.UUID;

public record LegalHoldResponse(
        UUID holdId,
        long version,
        String holdReference,
        String reason,
        rw.rra.roomiq.lifecycle.legalhold.LegalHoldScope scope,
        String status,
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
        String correlationId) {

    public static LegalHoldResponse from(LegalHoldRecord record) {
        return new LegalHoldResponse(record.holdId(), record.version(), record.holdReference(),
                record.reason(), record.scope(), record.status().name(), record.initiatingActor(),
                record.createdAt(), record.updatedBy(), record.updatedAt(), record.effectiveFrom(),
                record.approvedBy(), record.approvedAt(), record.releaseRequestedBy(),
                record.releaseRequestedAt(), record.releaseRequestEvidenceReference(),
                record.releasedBy(), record.releasedAt(), record.releaseVerificationReference(),
                record.supportingEvidenceReference(), record.lastEvidenceReference(), record.correlationId());
    }
}
