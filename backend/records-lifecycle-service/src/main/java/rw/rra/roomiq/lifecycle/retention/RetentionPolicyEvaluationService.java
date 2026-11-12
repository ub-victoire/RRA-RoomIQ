package rw.rra.roomiq.lifecycle.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataAccessException;
import rw.rra.roomiq.lifecycle.legalhold.LegalHoldEvaluationState;
import rw.rra.roomiq.lifecycle.legalhold.LegalHoldEventWriter;
import rw.rra.roomiq.lifecycle.legalhold.LegalHoldRepository;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class RetentionPolicyEvaluationService {
    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyEvaluationService.class);
    private final LifecyclePolicyVersionRepository versionRepository;
    private final LifecyclePolicyRuleRepository ruleRepository;
    private final RetentionEligibilityCalculator calculator;
    private final SourceOwnerEvidenceRepository sourceEvidenceRepository;
    private final RetentionEvaluationHistoryRepository historyRepository;
    private final LegalHoldRepository legalHoldRepository;
    private final LegalHoldEventWriter legalHoldEventWriter;
    private final Duration sourceEvidenceMaxAge;
    private final Clock clock;

    @Autowired
    public RetentionPolicyEvaluationService(LifecyclePolicyVersionRepository versionRepository,
                                            LifecyclePolicyRuleRepository ruleRepository,
                                            RetentionEligibilityCalculator calculator,
                                            SourceOwnerEvidenceRepository sourceEvidenceRepository,
                                            RetentionEvaluationHistoryRepository historyRepository,
                                            LegalHoldRepository legalHoldRepository,
                                            LegalHoldEventWriter legalHoldEventWriter,
                                            @Value("${roomiq.lifecycle.source-evidence.max-age:PT24H}")
                                            Duration sourceEvidenceMaxAge) {
        this(versionRepository, ruleRepository, calculator, sourceEvidenceRepository, historyRepository,
                legalHoldRepository, legalHoldEventWriter, sourceEvidenceMaxAge, Clock.systemUTC());
    }

    RetentionPolicyEvaluationService(LifecyclePolicyVersionRepository versionRepository,
                                     LifecyclePolicyRuleRepository ruleRepository,
                                     RetentionEligibilityCalculator calculator,
                                     SourceOwnerEvidenceRepository sourceEvidenceRepository,
                                     RetentionEvaluationHistoryRepository historyRepository,
                                     LegalHoldRepository legalHoldRepository,
                                     LegalHoldEventWriter legalHoldEventWriter,
                                     Duration sourceEvidenceMaxAge, Clock clock) {
        this.versionRepository = versionRepository;
        this.ruleRepository = ruleRepository;
        this.calculator = calculator;
        this.sourceEvidenceRepository = sourceEvidenceRepository;
        this.historyRepository = historyRepository;
        this.legalHoldRepository = legalHoldRepository;
        this.legalHoldEventWriter = legalHoldEventWriter;
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
            Instant evaluationAt,
            Long acknowledgedSourceRevision,
            Map<String, ExternalScheduleResult> externalScheduleResultsByRuleCode) {
        sourceEvidenceRepository.lockRecord(sourceService, recordType, recordId);
        SourceOwnerEvidenceReceipt receipt = sourceEvidenceRepository
                .findLatestAccepted(sourceService, recordType, recordId).orElse(null);
        LegalHoldEvaluationState holdState = loadApplicableHolds(sourceService, recordType, recordId,
                receipt == null ? null : receipt.evidence().categoryCode());
        if (receipt == null) {
            return recordEvaluation(sourceService, recordType, recordId, null, acknowledgedSourceRevision,
                    evaluationAt, externalScheduleResultsByRuleCode, holdState, null,
                    failedEvaluation(RetentionEvaluationResult.Reason.SOURCE_EVIDENCE_MISSING));
        }

        Long latestConflictRevision = sourceEvidenceRepository
                .findLatestConflictRevision(sourceService, recordType, recordId).orElse(null);
        if (latestConflictRevision != null
                && latestConflictRevision >= receipt.evidence().sourceRevision()) {
            return recordEvaluation(sourceService, recordType, recordId, receipt, acknowledgedSourceRevision,
                    evaluationAt, externalScheduleResultsByRuleCode, holdState, null,
                    failedEvaluation(RetentionEvaluationResult.Reason.SOURCE_EVIDENCE_CONFLICT));
        }

        if (receipt.receivedAt().isBefore(clock.instant().minus(sourceEvidenceMaxAge))) {
            return recordEvaluation(sourceService, recordType, recordId, receipt, acknowledgedSourceRevision,
                    evaluationAt, externalScheduleResultsByRuleCode, holdState, null,
                    failedEvaluation(RetentionEvaluationResult.Reason.SOURCE_EVIDENCE_STALE));
        }

        if (acknowledgedSourceRevision == null
                || acknowledgedSourceRevision != receipt.evidence().sourceRevision()) {
            return recordEvaluation(sourceService, recordType, recordId, receipt, acknowledgedSourceRevision,
                    evaluationAt, externalScheduleResultsByRuleCode, holdState, null,
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
                starts, externalScheduleResultsByRuleCode, holdState.available(), holdState.statuses());
        EvaluationContext context = compute(evaluationInput);
        return recordEvaluation(sourceService, recordType, recordId, receipt, acknowledgedSourceRevision,
                evaluationAt, externalScheduleResultsByRuleCode, holdState,
                evaluationInput, context);
    }

    private LegalHoldEvaluationState loadApplicableHolds(String sourceService, String recordType,
                                                         String recordId, String categoryCode) {
        try {
            legalHoldEventWriter.lockEvaluationScope(sourceService, recordType, recordId, categoryCode);
            return legalHoldRepository.findApplicable(sourceService, recordType, recordId, categoryCode);
        } catch (DataAccessException | IllegalStateException exception) {
            log.error("Legal hold state unavailable for retention evaluation sourceService={} recordType={}",
                    sourceService, recordType, exception);
            return LegalHoldEvaluationState.unavailable();
        }
    }

    private SourceEvidenceEvaluationResult recordEvaluation(
            String sourceService,
            String recordType,
            String recordId,
            SourceOwnerEvidenceReceipt receipt,
            Long acknowledgedSourceRevision,
            Instant evaluationAt,
            Map<String, ExternalScheduleResult> externalScheduleResultsByRuleCode,
            LegalHoldEvaluationState holdState,
            RetentionEvaluationEvidence evaluationInput,
            RetentionEvaluationResult evaluation) {
        return recordEvaluation(sourceService, recordType, recordId, receipt, acknowledgedSourceRevision,
                evaluationAt, externalScheduleResultsByRuleCode, holdState,
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
            LegalHoldEvaluationState holdState,
            RetentionEvaluationEvidence evaluationInput,
            EvaluationContext context) {
        RetentionEvaluationHistoryProvenance provenance = new RetentionEvaluationHistoryProvenance(
                clock.instant(), sourceService, recordType, recordId,
                receipt == null ? null : receipt.receiptId(),
                receipt == null ? null : receipt.evidence(),
                receipt == null ? null : receipt.evidenceSha256(),
                receipt == null ? null : receipt.receivedAt(),
                acknowledgedSourceRevision, evaluationAt,
                externalScheduleResultsByRuleCode, holdState.available(), holdState.statuses(),
                holdState.applicableHolds(), evaluationInput,
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
