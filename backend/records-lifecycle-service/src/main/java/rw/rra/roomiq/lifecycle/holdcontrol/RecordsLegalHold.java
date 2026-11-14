package rw.rra.roomiq.lifecycle.holdcontrol;

import java.time.Instant;
import java.util.UUID;

public record RecordsLegalHold(
        UUID holdId,
        String sourceService,
        String recordType,
        String recordId,
        String instructionReference,
        RecordsHoldStatus status,
        UUID createdByUserId,
        Instant createdAt,
        UUID releaseRequestedByUserId,
        Instant releaseRequestedAt,
        String releaseReference,
        UUID verifiedByUserId,
        Instant verifiedAt,
        String verificationReference,
        long version) {
}
