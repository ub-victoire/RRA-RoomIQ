package rw.rra.roomiq.lifecycle.legalhold.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LegalHoldEvidenceRequest(
        @NotBlank @Size(max = 256) String evidenceReference) {
}
