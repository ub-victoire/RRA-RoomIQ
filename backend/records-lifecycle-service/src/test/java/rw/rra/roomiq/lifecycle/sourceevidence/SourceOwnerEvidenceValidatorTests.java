package rw.rra.roomiq.lifecycle.sourceevidence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceOwnerEvidenceValidatorTests {
    private final SourceOwnerEvidenceValidator validator = new SourceOwnerEvidenceValidator();

    @Test
    void acceptsVersionOneEvidenceWhenAuthenticatedOwnerMatches() {
        SourceOwnerEvidence evidence = validEvidence();

        assertThat(validator.validate(evidence, "identity-service")).isEqualTo(evidence);
    }

    @Test
    void rejectsUnsupportedContractVersion() {
        SourceOwnerEvidence evidence = new SourceOwnerEvidence(2, "identity-service", "AUTHENTICATION",
                "event-1", UUID.randomUUID(), "AUTHENTICATION_SUCCEEDED", 1, 1,
                "ACCESS_AUTHENTICATION", Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-02T00:00:00Z"), Instant.parse("2024-01-02T00:00:00Z"),
                "ORIGINAL_EVENT", null, null, null, null, "correlation-1", null, null, null,
                Instant.parse("2024-01-02T00:00:00Z"));

        assertThatThrownBy(() -> validator.validate(evidence, "identity-service"))
                .isInstanceOf(SourceOwnerEvidenceValidationException.class)
                .hasMessageContaining("contract version");
    }

    @Test
    void rejectsMissingRequiredEvidence() {
        SourceOwnerEvidence evidence = new SourceOwnerEvidence(1, "identity-service", "AUTHENTICATION",
                "event-1", UUID.randomUUID(), "AUTHENTICATION_SUCCEEDED", 1, 1,
                "ACCESS_AUTHENTICATION", null, Instant.parse("2024-01-02T00:00:00Z"),
                Instant.parse("2024-01-02T00:00:00Z"), "ORIGINAL_EVENT", null, null, null,
                null, "correlation-1", null, null, null, Instant.parse("2024-01-02T00:00:00Z"));

        assertThatThrownBy(() -> validator.validate(evidence, "identity-service"))
                .isInstanceOf(SourceOwnerEvidenceValidationException.class)
                .hasMessageContaining("recordCreatedAt");
    }

    @Test
    void rejectsMalformedCategoryCodes() {
        SourceOwnerEvidence evidence = withCategory(validEvidence(), "access-authentication");

        assertThatThrownBy(() -> validator.validate(evidence, "identity-service"))
                .isInstanceOf(SourceOwnerEvidenceValidationException.class)
                .hasMessageContaining("categoryCode");
    }

    @Test
    void rejectsSourceClaimThatDoesNotMatchAuthenticatedOwner() {
        assertThatThrownBy(() -> validator.validate(validEvidence(), "booking-service"))
                .isInstanceOf(SourceOwnerEvidenceValidationException.class)
                .hasMessageContaining("authenticated source");
    }

    @Test
    void rejectsBookingCategoryNotInTheConfiguredAllowList() {
        SourceOwnerEvidence bookingEvidence = bookingEvidence(UUID.randomUUID().toString(),
                "BOOKING_REQUEST_STATUS_CHANGED", 1, "PENDING_APPROVAL", null, "UNMAPPED_CATEGORY");
        SourceOwnerEvidenceValidator configuredValidator = new SourceOwnerEvidenceValidator("KNOWN_CATEGORY");

        assertThatThrownBy(() -> configuredValidator.validate(bookingEvidence, "booking-service"))
                .isInstanceOf(SourceOwnerEvidenceValidationException.class)
                .hasMessageContaining("categoryCode");
    }

    @Test
    void rejectsBookingRecordIdsThatAreNotUuids() {
        assertThatThrownBy(() -> new SourceOwnerEvidenceValidator("KNOWN_CATEGORY")
                .validate(bookingEvidence("booking-1", "BOOKING_REQUEST_STATUS_CHANGED", 1,
                        "PENDING_APPROVAL", null, "KNOWN_CATEGORY"), "booking-service"))
                .isInstanceOf(SourceOwnerEvidenceValidationException.class)
                .hasMessageContaining("recordId");
    }

    @Test
    void rejectsUnsupportedBookingEventVersions() {
        assertThatThrownBy(() -> new SourceOwnerEvidenceValidator("KNOWN_CATEGORY")
                .validate(bookingEvidence(UUID.randomUUID().toString(), "BOOKING_REQUEST_STATUS_CHANGED", 2,
                        "PENDING_APPROVAL", null, "KNOWN_CATEGORY"), "booking-service"))
                .isInstanceOf(SourceOwnerEvidenceValidationException.class)
                .hasMessageContaining("event type/version");
    }

    @Test
    void requiresClosureTimestampToMatchTerminalBookingStatus() {
        assertThatThrownBy(() -> new SourceOwnerEvidenceValidator("KNOWN_CATEGORY")
                .validate(bookingEvidence(UUID.randomUUID().toString(), "BOOKING_REQUEST_STATUS_CHANGED", 1,
                        "CANCELLED", null, "KNOWN_CATEGORY"), "booking-service"))
                .isInstanceOf(SourceOwnerEvidenceValidationException.class)
                .hasMessageContaining("closedAt");
    }

    @Test
    void rejectsBookingEvidenceWithoutLifecycleStatus() {
        assertThatThrownBy(() -> new SourceOwnerEvidenceValidator("KNOWN_CATEGORY")
                .validate(bookingEvidence(UUID.randomUUID().toString(), "BOOKING_REQUEST_STATUS_CHANGED", 1,
                        null, null, "KNOWN_CATEGORY"), "booking-service"))
                .isInstanceOf(SourceOwnerEvidenceValidationException.class)
                .hasMessageContaining("currentLifecycleStatus");
    }

    @Test
    void requiresClosureTimestampForLaterDecisionClosureStart() {
        SourceOwnerEvidence evidence = withStartBasis(validEvidence(), "LATER_DECISION_CLOSURE");

        assertThatThrownBy(() -> validator.validate(evidence, "identity-service"))
                .isInstanceOf(SourceOwnerEvidenceValidationException.class)
                .hasMessageContaining("decisionAt and transactionClosedAt");
    }

    private static SourceOwnerEvidence validEvidence() {
        Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");
        Instant occurredAt = Instant.parse("2024-01-02T00:00:00Z");
        return new SourceOwnerEvidence(1, "identity-service", "AUTHENTICATION", "record-1",
                UUID.randomUUID(), "AUTHENTICATION_SUCCEEDED", 1, 1,
                "ACCESS_AUTHENTICATION", createdAt, occurredAt, occurredAt, "ORIGINAL_EVENT",
                null, null, null, null, "correlation-1", null, null, null, occurredAt);
    }

    private static SourceOwnerEvidence bookingEvidence(String recordId, String eventType, int eventVersion,
                                                       String status, Instant closedAt, String category) {
        Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");
        Instant occurredAt = Instant.parse("2024-01-02T00:00:00Z");
        return new SourceOwnerEvidence(1, "booking-service", "BOOKING_REQUEST", recordId,
                UUID.randomUUID(), eventType, eventVersion, 1, category, createdAt, occurredAt, occurredAt,
                "OCCURRED_AT", closedAt, null, null, null, "correlation-1", null, null,
                status, occurredAt);
    }

    private static SourceOwnerEvidence withCategory(SourceOwnerEvidence evidence, String categoryCode) {
        return new SourceOwnerEvidence(evidence.contractVersion(), evidence.sourceService(), evidence.recordType(),
                evidence.recordId(), evidence.eventId(), evidence.eventType(), evidence.eventVersion(),
                evidence.sourceRevision(), categoryCode, evidence.recordCreatedAt(), evidence.occurredAt(),
                evidence.authoritativeStartAt(), evidence.startBasisCode(), evidence.closedAt(),
                evidence.decisionAt(), evidence.transactionClosedAt(), evidence.actorSubject(),
                evidence.correlationId(), evidence.causationId(), evidence.supersedesEventId(),
                evidence.currentLifecycleStatus(), evidence.observedAt());
    }

    private static SourceOwnerEvidence withStartBasis(SourceOwnerEvidence evidence, String startBasisCode) {
        return new SourceOwnerEvidence(evidence.contractVersion(), evidence.sourceService(), evidence.recordType(),
                evidence.recordId(), evidence.eventId(), evidence.eventType(), evidence.eventVersion(),
                evidence.sourceRevision(), evidence.categoryCode(), evidence.recordCreatedAt(),
                evidence.occurredAt(), evidence.authoritativeStartAt(), startBasisCode, evidence.closedAt(),
                evidence.decisionAt(), evidence.transactionClosedAt(), evidence.actorSubject(),
                evidence.correlationId(), evidence.causationId(), evidence.supersedesEventId(),
                evidence.currentLifecycleStatus(), evidence.observedAt());
    }
}
