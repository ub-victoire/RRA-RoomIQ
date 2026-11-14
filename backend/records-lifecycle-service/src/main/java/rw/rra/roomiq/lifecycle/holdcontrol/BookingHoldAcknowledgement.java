package rw.rra.roomiq.lifecycle.holdcontrol;

import java.util.UUID;

public record BookingHoldAcknowledgement(
        UUID instructionId,
        UUID holdId,
        long instructionRevision,
        Outcome outcome,
        String enforcementStatus,
        Long bookingRequestVersion) {
    public enum Outcome {
        ACCEPTED,
        DUPLICATE,
        STALE,
        CONFLICTED,
        REJECTED,
        RECONCILIATION_REQUIRED
    }
}
