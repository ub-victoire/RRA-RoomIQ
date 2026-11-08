package rw.rra.roomiq.lifecycle.policy;

import java.time.Instant;
import java.util.UUID;

public record PolicyVersionDefinition(
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
        PolicyStatus status,
        Instant createdAt) {
}
