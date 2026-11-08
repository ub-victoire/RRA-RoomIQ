package rw.rra.roomiq.lifecycle.retention;

import rw.rra.roomiq.lifecycle.policy.PolicyRuleSource;
import rw.rra.roomiq.lifecycle.policy.RetentionDurationUnit;

import java.util.UUID;

public record RetentionRuleSnapshot(
        UUID policyVersionId,
        String ruleCode,
        String categoryCode,
        PolicyRuleSource ruleSource,
        Short durationValue,
        RetentionDurationUnit durationUnit,
        String startBasisCode,
        String scheduleReference) {
}
