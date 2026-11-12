package rw.rra.roomiq.lifecycle.sourceevidence;

import java.time.Instant;
import java.util.UUID;

public record SourceOwnerEvidenceIngestionResult(
        UUID eventId,
        long sourceRevision,
        String evidenceSha256,
        Instant receivedAt,
        SourceOwnerEvidenceOutcome outcome,
        String reason) {
}
