package rw.rra.roomiq.lifecycle.legalhold.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import rw.rra.roomiq.lifecycle.legalhold.LegalHoldScope;

public record AmendLegalHoldRequest(
        @NotBlank @Size(max = 1000) String reason,
        @NotNull @Valid LegalHoldScope scope,
        @NotBlank @Size(max = 256) String evidenceReference) {
}
