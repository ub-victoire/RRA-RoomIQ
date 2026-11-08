package rw.rra.roomiq.lifecycle.policy.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import rw.rra.roomiq.lifecycle.policy.PolicyRuleSource;
import rw.rra.roomiq.lifecycle.policy.RetentionDurationUnit;

import java.util.UUID;

public record PolicyRuleRequest(
        @NotNull UUID ruleId,
        @NotBlank @Size(max = 64) String ruleCode,
        @NotBlank @Size(max = 64) String categoryCode,
        @NotNull PolicyRuleSource ruleSource,
        @Positive Short durationValue,
        RetentionDurationUnit durationUnit,
        @NotBlank @Size(max = 40) String startBasisCode,
        @Size(max = 256) String scheduleReference) {
}
