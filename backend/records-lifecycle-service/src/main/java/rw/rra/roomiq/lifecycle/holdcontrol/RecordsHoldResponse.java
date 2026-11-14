package rw.rra.roomiq.lifecycle.holdcontrol;

import java.time.Instant;
import java.util.UUID;

public record RecordsHoldResponse(
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
    static RecordsHoldResponse from(RecordsLegalHold hold) {
        return new RecordsHoldResponse(hold.holdId(), hold.sourceService(), hold.recordType(), hold.recordId(),
                hold.instructionReference(), hold.status(), hold.createdByUserId(), hold.createdAt(),
                hold.releaseRequestedByUserId(), hold.releaseRequestedAt(), hold.releaseReference(),
                hold.verifiedByUserId(), hold.verifiedAt(), hold.verificationReference(), hold.version());
    }
}
