package rw.rra.roomiq.lifecycle.retention;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
import rw.rra.roomiq.lifecycle.holdcontrol.RecordsHoldRepository;
import rw.rra.roomiq.lifecycle.holdcontrol.RecordsLegalHold;

import java.util.List;
import java.util.HashMap;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class RetentionPolicyEvaluationService {
    private final LifecyclePolicyVersionRepository versionRepository;
    private final LifecyclePolicyRuleRepository ruleRepository;
    private final RetentionEligibilityCalculator calculator;
    private final SourceOwnerEvidenceRepository sourceEvidenceRepository;
    private final RecordsHoldRepository recordsHoldRepository;
    private final RetentionEvaluationHistoryRepository historyRepository;
    private final Duration sourceEvidenceMaxAge;
    private final Clock clock;

    @Autowired
    public RetentionPolicyEvaluationService(LifecyclePolicyVersionRepository versionRepository,
                                            LifecyclePolicyRuleRepository ruleRepository,
                                            RetentionEligibilityCalculator calculator,
                                            SourceOwnerEvidenceRepository sourceEvidenceRepository,
                                            RecordsHoldRepository recordsHoldRepository,
                                            RetentionEvaluationHistoryRepository historyRepository,
                                            @Value("${roomiq.lifecycle.source-evidence.max-age:PT24H}")
                                            Duration sourceEvidenceMaxAge, Clock clock) {
        this.versionRepository = versionRepository;
        this.ruleRepository = ruleRepository;
        this.calculator = calculator;
        this.sourceEvidenceRepository = sourceEvidenceRepository;
        this.recordsHoldRepository = recordsHoldRepository;
        this.historyRepository = historyRepository;
        if (sourceEvidenceMaxAge.isNegative() || sourceEvidenceMaxAge.isZero()) {
            throw new IllegalArgumentException("Source evidence max age must be positive");
        }
        this.sourceEvidenceMaxAge = sourceEvidenceMaxAge;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RetentionEvaluationResult evaluate(RetentionEvaluationEvidence evidence) {
        if (evidence == null) {
            return calculator.evaluate(null);
        }

        return compute(new RetentionEvaluationEvidence(evidence.ownerKnown(), evidence.ownerAcknowledged(),
                evidence.categoryCode(), evidence.authoritativeStartAt(), clock.instant(),
                evidence.startTimestampsByBasis(), evidence.externalScheduleResultsByRuleCode(),
                evidence.holdStateAvailable(), evidence.holdStatuses())).result();
    }

    @Transactional(readOnly = true)
    public RetentionEvaluationResult evaluateAt(RetentionEvaluationEvidence evidence) {
        if (evidence == null) {
            return calculator.evaluate(null);
        }
        return compute(evidence).result();
    }

    private EvaluationContext compute(RetentionEvaluationEvidence evidence) {
        List<LifecyclePolicyVersion> policyEntities = evidence.authoritativeStartAt() == null ? List.of()
                : versionRepository.findEffectiveAt(evidence.authoritativeStartAt());
        List<PolicyVersionSnapshot> versions = policyEntities.stream()
                .map(RetentionPolicyEvaluationService::toSnapshot).toList();
        List<UUID> versionIds = versions.stream().map(PolicyVersionSnapshot::id).toList();
        List<LifecyclePolicyRule> ruleEntities = versionIds.isEmpty() || evidence.categoryCode() == null
                ? List.of() : ruleRepository.findByPolicyVersion_PolicyVersionIdInAndCategoryCode(
                        versionIds, evidence.categoryCode()).stream()
                .toList();
        List<RetentionRuleSnapshot> rules = ruleEntities.stream()
                .map(RetentionPolicyEvaluationService::toSnapshot).toList();

        RetentionEvaluationResult result = calculator.evaluate(new RetentionEvaluationInput(
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
        return new EvaluationContext(result, policyEntities, rules);
    }

    @Transactional
    public SourceEvidenceEvaluationResult evaluateLatestEvidence(
            String sourceService,
            String recordType,
            String recordId,
            Long acknowledgedSourceRevision,
            Map<String, ExternalScheduleResult> externalScheduleResultsByRuleCode) {
        return evaluateLatestEvidenceAtTime(sourceService, recordType, recordId, clock.instant(),
                acknowledgedSourceRevision, externalScheduleResultsByRuleCode);
    }

    @Transactional
    public SourceEvidenceEvaluationResult evaluateLatestEvidenceAt(
            String sourceService,
            String recordType,
            String recordId,
            Instant evaluationAt,
            Long acknowledgedSourceRevision,
            Map<String, ExternalScheduleResult> externalScheduleResultsByRuleCode) {
        return evaluateLatestEvidenceAtTime(sourceService, recordType, recordId, evaluationAt,
                acknowledgedSourceRevision, externalScheduleResultsByRuleCode);
    }

    private SourceEvidenceEvaluationResult evaluateLatestEvidenceAtTime(
            String sourceService,
            String recordType,
            String recordId,
            Instant evaluationAt,
            Long acknowledgedSourceRevision,
            Map<String, ExternalScheduleResult> externalScheduleResultsByRuleCode) {
        sourceEvidenceRepository.lockRecord(sourceService, recordType, recordId);
            boolean holdStateAvailable = hasHoldCoverage(sourceService, recordType);
            List<RecordsLegalHold> holds = holdStateAvailable
                    ? recordsHoldRepository.findHolds(sourceService, recordType, recordId) : List.of();
            List<HoldStatus> holdStatuses = holds.stream()
                    .map(hold -> HoldStatus.valueOf(hold.status().name())).toList();
            List<RecordsHoldEvaluationProvenance> holdEvidence = holds.stream()
                    .map(RecordsHoldEvaluationProvenance::from).toList();
            SourceOwnerEvidenceReceipt receipt = sourceEvidenceRepository
                    .findLatestAccepted(sourceService, recordType, recordId).orElse(null);
            if (receipt == null) {
                return recordEvaluation(sourceService, recordType, recordId, null, acknowledgedSourceRevision,
                        evaluationAt, externalScheduleResultsByRuleCode, holdStatuses, holdEvidence, null,
                        failedEvaluation(RetentionEvaluationResult.Reason.SOURCE_EVIDENCE_MISSING));
        }

        Long latestConflictRevision = sourceEvidenceRepository
                .findLatestConflictRevision(sourceService, recordType, recordId).orElse(null);
        if (latestConflictRevision != null
                && latestConflictRevision >= receipt.evidence().sourceRevision()) {
            return recordEvaluation(sourceService, recordType, recordId, receipt, acknowledgedSourceRevision,
                    evaluationAt, externalScheduleResultsByRuleCode, holdStatuses, holdEvidence, null,
                    failedEvaluation(RetentionEvaluationResult.Reason.SOURCE_EVIDENCE_CONFLICT));
        }

        if (receipt.receivedAt().isBefore(clock.instant().minus(sourceEvidenceMaxAge))) {
            return recordEvaluation(sourceService, recordType, recordId, receipt, acknowledgedSourceRevision,
                    evaluationAt, externalScheduleResultsByRuleCode, holdStatuses, holdEvidence, null,
                    failedEvaluation(RetentionEvaluationResult.Reason.SOURCE_EVIDENCE_STALE));
        }

        if (acknowledgedSourceRevision == null
                || acknowledgedSourceRevision != receipt.evidence().sourceRevision()) {
            return recordEvaluation(sourceService, recordType, recordId, receipt, acknowledgedSourceRevision,
                    evaluationAt, externalScheduleResultsByRuleCode, holdStatuses, holdEvidence, null,
                    failedEvaluation(RetentionEvaluationResult.Reason.OWNER_UNACKNOWLEDGED));
        }

        var sourceEvidence = receipt.evidence();
        Map<String, Instant> starts = new HashMap<>();
        starts.put(sourceEvidence.startBasisCode(), sourceEvidence.authoritativeStartAt());
        if ("LATER_DECISION_CLOSURE".equals(sourceEvidence.startBasisCode())) {
            if (sourceEvidence.decisionAt() != null) {
                starts.put("DECISION_DATE", sourceEvidence.decisionAt());
            }
            if (sourceEvidence.transactionClosedAt() != null) {
                starts.put("TRANSACTION_CLOSURE", sourceEvidence.transactionClosedAt());
            }
        }

        RetentionEvaluationEvidence evaluationInput = new RetentionEvaluationEvidence(
                true, true, sourceEvidence.categoryCode(), sourceEvidence.authoritativeStartAt(), evaluationAt,
                starts, externalScheduleResultsByRuleCode, holdStateAvailable, holdStatuses);
        EvaluationContext context = compute(evaluationInput);
        return recordEvaluation(sourceService, recordType, recordId, receipt, acknowledgedSourceRevision,
                evaluationAt, externalScheduleResultsByRuleCode, holdStatuses, holdEvidence,
                evaluationInput, context);
    }

    private static boolean hasHoldCoverage(String sourceService, String recordType) {
        return "booking-service".equals(sourceService) && "BOOKING_REQUEST".equals(recordType);
    }

    private SourceEvidenceEvaluationResult recordEvaluation(
            String sourceService,
            String recordType,
            String recordId,
            SourceOwnerEvidenceReceipt receipt,
            Long acknowledgedSourceRevision,
            Instant evaluationAt,
            Map<String, ExternalScheduleResult> externalScheduleResultsByRuleCode,
            List<HoldStatus> holdStatuses,
            List<RecordsHoldEvaluationProvenance> holdEvidence,
            RetentionEvaluationEvidence evaluationInput,
            RetentionEvaluationResult evaluation) {
        return recordEvaluation(sourceService, recordType, recordId, receipt, acknowledgedSourceRevision,
                evaluationAt, externalScheduleResultsByRuleCode, holdStatuses, holdEvidence,
                evaluationInput, new EvaluationContext(evaluation, List.of(), List.of()));
    }

    private SourceEvidenceEvaluationResult recordEvaluation(
            String sourceService,
            String recordType,
            String recordId,
            SourceOwnerEvidenceReceipt receipt,
            Long acknowledgedSourceRevision,
            Instant evaluationAt,
            Map<String, ExternalScheduleResult> externalScheduleResultsByRuleCode,
            List<HoldStatus> holdStatuses,
            List<RecordsHoldEvaluationProvenance> holdEvidence,
            RetentionEvaluationEvidence evaluationInput,
            EvaluationContext context) {
        RetentionEvaluationHistoryProvenance provenance = new RetentionEvaluationHistoryProvenance(
                clock.instant(), sourceService, recordType, recordId,
                receipt == null ? null : receipt.receiptId(),
                receipt == null ? null : receipt.evidence(),
                receipt == null ? null : receipt.evidenceSha256(),
                receipt == null ? null : receipt.receivedAt(),
                acknowledgedSourceRevision, evaluationAt,
                externalScheduleResultsByRuleCode, true, holdStatuses, holdEvidence, evaluationInput,
                context.policyVersions().stream().map(RetentionPolicyEvaluationService::toProvenance).toList(),
                context.rules(), context.result());
        UUID evaluationId = historyRepository.append(provenance, receipt);
        return new SourceEvidenceEvaluationResult(
                receipt == null ? null : receipt.evidence().eventId(),
                receipt == null ? 0 : receipt.evidence().sourceRevision(),
                receipt == null ? null : receipt.evidenceSha256(),
                receipt == null ? null : receipt.receivedAt(),
                context.result(), evaluationId,
                receipt == null ? null : receipt.evidence().categoryCode(),
                receipt == null ? null : receipt.evidence().authoritativeStartAt(),
                evaluationAt);
    }

    private static RetentionEvaluationResult failedEvaluation(RetentionEvaluationResult.Reason reason) {
        return new RetentionEvaluationResult(RetentionEvaluationResult.Status.NOT_ELIGIBLE, reason,
                null, null, List.of());
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

    private static RetentionEvaluationHistoryProvenance.PolicyVersionProvenance toProvenance(
            LifecyclePolicyVersion version) {
        return new RetentionEvaluationHistoryProvenance.PolicyVersionProvenance(
                version.getPolicyVersionId(), version.getVersionLabel(), version.getDocumentSha256(),
                version.getApprovalReference(), version.getPolicyVersion(), version.getEffectiveFrom(),
                version.getEffectiveTo(), version.getApprovingAuthorityRef(), version.getApprovedBySubject(),
                version.getApprovedAt(), version.getLegalValidationRef(), version.getRecordsValidationRef(),
                version.getStatus(), version.getCreatedAt());
    }

    private record EvaluationContext(
            RetentionEvaluationResult result,
            List<LifecyclePolicyVersion> policyVersions,
            List<RetentionRuleSnapshot> rules) {
    }
}
