package rw.rra.roomiq.lifecycle.retention;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.lifecycle.policy.LifecyclePolicyRule;
import rw.rra.roomiq.lifecycle.policy.LifecyclePolicyRuleRepository;
import rw.rra.roomiq.lifecycle.policy.LifecyclePolicyVersion;
import rw.rra.roomiq.lifecycle.policy.LifecyclePolicyVersionRepository;
import rw.rra.roomiq.lifecycle.policy.PolicyRuleSource;
import rw.rra.roomiq.lifecycle.policy.PolicyStatus;
import rw.rra.roomiq.lifecycle.policy.RetentionDurationUnit;
import rw.rra.roomiq.lifecycle.sourceevidence.SourceOwnerEvidenceReceipt;
import rw.rra.roomiq.lifecycle.sourceevidence.SourceOwnerEvidenceRepository;

import java.util.List;
import java.util.HashMap;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class RetentionPolicyEvaluationService {
    private final LifecyclePolicyVersionRepository versionRepository;
    private final LifecyclePolicyRuleRepository ruleRepository;
    private final RetentionEligibilityCalculator calculator;
    private final SourceOwnerEvidenceRepository sourceEvidenceRepository;

    public RetentionPolicyEvaluationService(LifecyclePolicyVersionRepository versionRepository,
                                            LifecyclePolicyRuleRepository ruleRepository,
                                            RetentionEligibilityCalculator calculator,
                                            SourceOwnerEvidenceRepository sourceEvidenceRepository) {
        this.versionRepository = versionRepository;
        this.ruleRepository = ruleRepository;
        this.calculator = calculator;
        this.sourceEvidenceRepository = sourceEvidenceRepository;
    }

    @Transactional(readOnly = true)
    public RetentionEvaluationResult evaluate(RetentionEvaluationEvidence evidence) {
        if (evidence == null) {
            return calculator.evaluate(null);
        }

        List<PolicyVersionSnapshot> versions = evidence.authoritativeStartAt() == null ? List.of()
                : versionRepository.findEffectiveAt(evidence.authoritativeStartAt()).stream()
                .map(RetentionPolicyEvaluationService::toSnapshot)
                .toList();
        List<UUID> versionIds = versions.stream().map(PolicyVersionSnapshot::id).toList();
        List<RetentionRuleSnapshot> rules = versionIds.isEmpty() || evidence.categoryCode() == null
                ? List.of()
                : ruleRepository.findByPolicyVersion_PolicyVersionIdInAndCategoryCode(
                        versionIds, evidence.categoryCode()).stream()
                .map(RetentionPolicyEvaluationService::toSnapshot)
                .toList();

        return calculator.evaluate(new RetentionEvaluationInput(
                evidence.ownerKnown(),
                evidence.ownerAcknowledged(),
                evidence.categoryCode(),
                evidence.authoritativeStartAt(),
                evidence.evaluationAt(),
                versions,
                rules,
                evidence.startTimestampsByBasis(),
                evidence.externalScheduleResultsByRuleCode(),
                evidence.holdStateAvailable(),
                evidence.holdStatuses()));
    }

    @Transactional(readOnly = true)
    public SourceEvidenceEvaluationResult evaluateLatestEvidence(
            String sourceService,
            String recordType,
            String recordId,
            Instant evaluationAt,
            Long acknowledgedSourceRevision,
            Map<String, ExternalScheduleResult> externalScheduleResultsByRuleCode,
            boolean holdStateAvailable,
            List<HoldStatus> holdStatuses) {
        SourceOwnerEvidenceReceipt receipt = sourceEvidenceRepository
                .findLatestAccepted(sourceService, recordType, recordId).orElse(null);
        if (receipt == null) {
            return failedEvidenceEvaluation(RetentionEvaluationResult.Reason.SOURCE_EVIDENCE_MISSING);
        }

        Long latestConflictRevision = sourceEvidenceRepository
                .findLatestConflictRevision(sourceService, recordType, recordId).orElse(null);
        if (latestConflictRevision != null
                && latestConflictRevision >= receipt.evidence().sourceRevision()) {
            return failedEvidenceEvaluation(RetentionEvaluationResult.Reason.SOURCE_EVIDENCE_CONFLICT);
        }

        if (acknowledgedSourceRevision == null
                || acknowledgedSourceRevision != receipt.evidence().sourceRevision()) {
            return new SourceEvidenceEvaluationResult(receipt.evidence().eventId(),
                    receipt.evidence().sourceRevision(), receipt.evidenceSha256(), receipt.receivedAt(),
                    new RetentionEvaluationResult(RetentionEvaluationResult.Status.NOT_ELIGIBLE,
                            RetentionEvaluationResult.Reason.OWNER_UNACKNOWLEDGED, null, null, List.of()));
        }

        var sourceEvidence = receipt.evidence();
        Map<String, Instant> starts = new HashMap<>();
        starts.put(sourceEvidence.startBasisCode(), sourceEvidence.authoritativeStartAt());
        starts.put("RECORD_CREATED_AT", sourceEvidence.recordCreatedAt());
        starts.put("OCCURRED_AT", sourceEvidence.occurredAt());
        starts.put("ORIGINAL_EVENT", sourceEvidence.occurredAt());
        if (sourceEvidence.closedAt() != null) {
            starts.put("CLOSED_AT", sourceEvidence.closedAt());
        }
        if (sourceEvidence.decisionAt() != null) {
            starts.put("DECISION_DATE", sourceEvidence.decisionAt());
        }
        if (sourceEvidence.transactionClosedAt() != null) {
            starts.put("TRANSACTION_CLOSURE", sourceEvidence.transactionClosedAt());
        }

        RetentionEvaluationResult evaluation = evaluate(new RetentionEvaluationEvidence(
                true, true, sourceEvidence.categoryCode(), sourceEvidence.authoritativeStartAt(), evaluationAt,
                starts, externalScheduleResultsByRuleCode, holdStateAvailable, holdStatuses));
        return new SourceEvidenceEvaluationResult(sourceEvidence.eventId(), sourceEvidence.sourceRevision(),
                receipt.evidenceSha256(), receipt.receivedAt(), evaluation);
    }

    private static SourceEvidenceEvaluationResult failedEvidenceEvaluation(RetentionEvaluationResult.Reason reason) {
        return new SourceEvidenceEvaluationResult(null, 0, null, null,
                new RetentionEvaluationResult(RetentionEvaluationResult.Status.NOT_ELIGIBLE, reason,
                        null, null, List.of()));
    }

    private static PolicyVersionSnapshot toSnapshot(LifecyclePolicyVersion version) {
        return new PolicyVersionSnapshot(version.getPolicyVersionId(), version.getPolicyVersion(),
                PolicyStatus.valueOf(version.getStatus()), version.getEffectiveFrom(), version.getEffectiveTo());
    }

    private static RetentionRuleSnapshot toSnapshot(LifecyclePolicyRule rule) {
        return new RetentionRuleSnapshot(rule.getPolicyVersionId(), rule.getRuleCode(), rule.getCategoryCode(),
                PolicyRuleSource.valueOf(rule.getRuleSource()),
                rule.getDurationValue(),
                rule.getDurationUnit() == null ? null : RetentionDurationUnit.valueOf(rule.getDurationUnit()),
                rule.getStartBasisCode(), rule.getScheduleReference());
    }
}
