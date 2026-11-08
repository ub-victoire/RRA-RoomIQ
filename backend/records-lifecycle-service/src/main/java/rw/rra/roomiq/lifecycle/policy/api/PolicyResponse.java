package rw.rra.roomiq.lifecycle.policy.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PolicyResponse(
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
        Instant createdAt,
        List<PolicyRuleResponse> rules) {
}
