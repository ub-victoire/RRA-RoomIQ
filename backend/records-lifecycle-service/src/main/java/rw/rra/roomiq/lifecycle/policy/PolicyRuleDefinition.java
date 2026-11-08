package rw.rra.roomiq.lifecycle.policy;

import java.time.Instant;
import java.util.UUID;

public record PolicyRuleDefinition(
        UUID ruleId,
        UUID policyVersionId,
        String ruleCode,
        String categoryCode,
        PolicyRuleSource ruleSource,
        Short durationValue,
        RetentionDurationUnit durationUnit,
        String startBasisCode,
        String scheduleReference,
        Instant createdAt) {
}
