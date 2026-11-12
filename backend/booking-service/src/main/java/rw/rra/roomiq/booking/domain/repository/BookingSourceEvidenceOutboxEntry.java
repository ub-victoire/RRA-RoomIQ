package rw.rra.roomiq.booking.domain.repository;

import java.time.Instant;
import java.util.UUID;

public record BookingSourceEvidenceOutboxEntry(
        UUID id,
        UUID bookingRequestId,
        String requestType,
        long sourceRevision,
        UUID eventId,
        UUID supersedesEventId,
        String categoryCode,
        String startBasisCode,
        Instant authoritativeStartAt,
        String currentLifecycleStatus,
        Instant recordCreatedAt,
        Instant occurredAt,
        Instant observedAt,
        Instant closedAt,
        Instant decisionAt,
        String correlationId,
        int attemptCount) {
}
