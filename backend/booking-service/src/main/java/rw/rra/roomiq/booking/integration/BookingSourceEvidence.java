package rw.rra.roomiq.booking.integration;

import java.time.Instant;
import java.util.UUID;

public record BookingSourceEvidence(
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

    public static BookingSourceEvidence from(BookingSourceEvidenceOutboxRecord entry) {
        return new BookingSourceEvidence(1, "booking-service", "BOOKING_REQUEST", entry.recordId().toString(),
                entry.eventId(), "BOOKING_REQUEST_STATUS_CHANGED", 1, entry.sourceRevision(),
                entry.categoryCode(), entry.recordCreatedAt(), entry.occurredAt(), entry.authoritativeStartAt(),
                entry.startBasisCode(), entry.closedAt(), entry.decisionAt(), null, null,
                entry.correlationId(), null, entry.supersedesEventId(), entry.currentLifecycleStatus(),
                entry.observedAt());
    }

    public record BookingSourceEvidenceOutboxRecord(
            UUID recordId,
            UUID eventId,
            long sourceRevision,
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
            String correlationId) {
    }
}
