package rw.rra.roomiq.lifecycle.retention;

import org.junit.jupiter.api.Test;
import rw.rra.roomiq.lifecycle.policy.LifecyclePolicyRuleRepository;
import rw.rra.roomiq.lifecycle.policy.LifecyclePolicyVersionRepository;
import rw.rra.roomiq.lifecycle.sourceevidence.SourceOwnerEvidence;
import rw.rra.roomiq.lifecycle.sourceevidence.SourceOwnerEvidenceOutcome;
import rw.rra.roomiq.lifecycle.sourceevidence.SourceOwnerEvidenceReceipt;
import rw.rra.roomiq.lifecycle.sourceevidence.SourceOwnerEvidenceRepository;
import rw.rra.roomiq.lifecycle.holdcontrol.RecordsHoldRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetentionPolicyEvaluationServiceTests {
    @Test
    void staleAcknowledgedSourceEvidenceCannotProduceEligibility() {
        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        SourceOwnerEvidence evidence = new SourceOwnerEvidence(1, "booking-service", "BOOKING_REQUEST",
                "booking-1", UUID.randomUUID(), "BOOKING_REQUEST_STATUS_CHANGED", 1, 4,
                "TEST_CATEGORY", now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(29)),
                now.minus(Duration.ofDays(30)), "RECORD_CREATED_AT", null, null, null, null,
                "correlation-1", null, null, "PENDING_APPROVAL", now.minus(Duration.ofDays(29)));
        SourceOwnerEvidenceReceipt receipt = new SourceOwnerEvidenceReceipt(1, evidence, "booking-service",
                now.minus(Duration.ofHours(25)), "a".repeat(64), SourceOwnerEvidenceOutcome.ACCEPTED, null);
        SourceOwnerEvidenceRepository sourceEvidenceRepository = mock(SourceOwnerEvidenceRepository.class);
        RecordsHoldRepository recordsHoldRepository = mock(RecordsHoldRepository.class);
        when(sourceEvidenceRepository.findLatestAccepted("booking-service", "BOOKING_REQUEST", "booking-1"))
                .thenReturn(Optional.of(receipt));
        when(sourceEvidenceRepository.findLatestConflictRevision("booking-service", "BOOKING_REQUEST", "booking-1"))
                .thenReturn(Optional.empty());
        when(recordsHoldRepository.findHolds("booking-service", "BOOKING_REQUEST", "booking-1"))
                .thenReturn(List.of());

        RetentionPolicyEvaluationService service = new RetentionPolicyEvaluationService(
                mock(LifecyclePolicyVersionRepository.class),
                mock(LifecyclePolicyRuleRepository.class),
                mock(RetentionEligibilityCalculator.class),
                sourceEvidenceRepository,
                recordsHoldRepository,
                mock(RetentionEvaluationHistoryRepository.class),
                Duration.ofHours(24),
                Clock.fixed(now, ZoneOffset.UTC));

        SourceEvidenceEvaluationResult result = service.evaluateLatestEvidence(
                "booking-service", "BOOKING_REQUEST", "booking-1", now, 4L, Map.of());

        assertThat(result.evaluation().status()).isEqualTo(RetentionEvaluationResult.Status.NOT_ELIGIBLE);
        assertThat(result.evaluation().reason()).isEqualTo(RetentionEvaluationResult.Reason.SOURCE_EVIDENCE_STALE);
    }
}
