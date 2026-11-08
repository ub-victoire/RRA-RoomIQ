package rw.rra.roomiq.lifecycle.policy.api;

import java.util.UUID;

public record PolicyRuleResponse(
        UUID ruleId,
        String ruleCode,
        String categoryCode,
        String ruleSource,
        Short durationValue,
        String durationUnit,
        String startBasisCode,
        String scheduleReference) {
}
