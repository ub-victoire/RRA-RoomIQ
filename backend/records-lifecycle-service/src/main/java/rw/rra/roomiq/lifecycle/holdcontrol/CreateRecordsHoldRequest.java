package rw.rra.roomiq.lifecycle.holdcontrol;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateRecordsHoldRequest(
        @NotNull UUID holdId,
        @NotBlank @Size(max = 64) String sourceService,
        @NotBlank @Size(max = 64) String recordType,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String recordId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}") String instructionReference) {
}
