package rw.rra.roomiq.lifecycle.holdcontrol;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyRecordsHoldReleaseRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}") String verificationReference) {
}
