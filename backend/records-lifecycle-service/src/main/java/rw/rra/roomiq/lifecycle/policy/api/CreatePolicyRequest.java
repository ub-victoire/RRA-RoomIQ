package rw.rra.roomiq.lifecycle.policy.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreatePolicyRequest(
        @NotNull UUID policyVersionId,
        @NotBlank @Size(max = 64) String versionLabel,
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String documentSha256,
        @NotEmpty List<@Valid PolicyRuleRequest> rules) {
}
