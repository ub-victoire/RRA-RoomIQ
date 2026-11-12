package rw.rra.roomiq.lifecycle.retention;

import rw.rra.roomiq.lifecycle.sourceevidence.SourceOwnerEvidence;
import rw.rra.roomiq.lifecycle.legalhold.ApplicableLegalHold;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RetentionEvaluationHistoryProvenance(
        Instant recordedAt,
        String sourceService,
        String recordType,
        String recordId,
        Long sourceEvidenceReceiptId,
        SourceOwnerEvidence sourceEvidence,
        String sourceEvidenceSha256,
        Instant evidenceReceivedAt,
        Long acknowledgedSourceRevision,
        Instant evaluationAt,
        Map<String, ExternalScheduleResult> externalScheduleResultsByRuleCode,
        boolean holdStateAvailable,
        List<HoldStatus> holdStatuses,
        List<ApplicableLegalHold> applicableHolds,
        RetentionEvaluationEvidence evaluationInput,
        List<PolicyVersionProvenance> candidatePolicyVersions,
        List<RetentionRuleSnapshot> candidateRules,
        RetentionEvaluationResult evaluation) {

    public record PolicyVersionProvenance(
            UUID policyVersionId,
            String versionLabel,
            String documentSha256,
            String approvalReference,
            String policyVersion,
            Instant effectiveFrom,
            Instant effectiveTo,
            String approvingAuthorityRef,
            String approvedBySubject,
            Instant approvedAt,
            String legalValidationRef,
            String recordsValidationRef,
            String status,
            Instant createdAt) {
    }
}
