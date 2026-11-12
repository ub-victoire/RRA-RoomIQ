package rw.rra.roomiq.lifecycle.legalhold;

import rw.rra.roomiq.lifecycle.retention.HoldStatus;

import java.util.UUID;

public record ApplicableLegalHold(
        UUID holdId,
        String holdReference,
        LegalHoldStatus lifecycleStatus,
        HoldStatus evaluationStatus,
        long version,
        LegalHoldScope scope) {
}
