package rw.rra.roomiq.lifecycle.retention;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RetentionEvaluationResult(
        Status status,
        Reason reason,
        Instant retainUntil,
        UUID policyVersionId,
        List<String> applicableRuleCodes) {
    public enum Status {
        NOT_ELIGIBLE,
        ELIGIBLE
    }

    public enum Reason {
        OWNER_UNKNOWN,
        OWNER_UNACKNOWLEDGED,
        SOURCE_EVIDENCE_MISSING,
        SOURCE_EVIDENCE_CONFLICT,
        CATEGORY_UNKNOWN,
        AUTHORITATIVE_START_MISSING,
        EVALUATION_TIME_MISSING,
        NO_APPLICABLE_POLICY,
        CONFLICTING_POLICIES,
        POLICY_NOT_APPROVED,
        NO_APPLICABLE_RULES,
        RULE_START_MISSING,
        RETENTION_EXPIRY_UNCOMPUTABLE,
        SCHEDULE_RESULT_MISSING_OR_CONFLICTING,
        HOLD_STATE_UNAVAILABLE,
        HOLD_BLOCKS_DISPOSAL,
        RETENTION_NOT_EXPIRED,
        RETENTION_EXPIRED
    }

    public boolean eligible() {
        return status == Status.ELIGIBLE;
    }
}
