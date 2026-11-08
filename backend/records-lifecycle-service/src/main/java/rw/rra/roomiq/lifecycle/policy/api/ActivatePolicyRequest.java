package rw.rra.roomiq.lifecycle.policy.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record ActivatePolicyRequest(
        @NotBlank @Size(max = 128) String approvalReference,
        @NotBlank @Size(max = 64) String policyVersion,
        @NotNull Instant effectiveFrom,
        Instant effectiveTo,
        @NotBlank @Size(max = 128) String approvingAuthorityRef,
        @NotBlank @Size(max = 128) String legalValidationRef,
        @NotBlank @Size(max = 128) String recordsValidationRef) {
}
