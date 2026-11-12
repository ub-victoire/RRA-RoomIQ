package rw.rra.roomiq.lifecycle.sourceevidence;

import java.time.Instant;
import java.util.UUID;

public record SourceOwnerEvidence(
        int contractVersion,
        String sourceService,
        String recordType,
        String recordId,
        UUID eventId,
        String eventType,
        int eventVersion,
        long sourceRevision,
        String categoryCode,
        Instant recordCreatedAt,
        Instant occurredAt,
        Instant authoritativeStartAt,
        String startBasisCode,
        Instant closedAt,
        Instant decisionAt,
        Instant transactionClosedAt,
        String actorSubject,
        String correlationId,
        String causationId,
        UUID supersedesEventId,
        String currentLifecycleStatus,
        Instant observedAt) {
    public static final int SUPPORTED_CONTRACT_VERSION = 1;
}
