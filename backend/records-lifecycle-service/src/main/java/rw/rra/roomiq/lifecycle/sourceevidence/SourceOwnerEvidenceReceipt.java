package rw.rra.roomiq.lifecycle.sourceevidence;

import java.time.Instant;

public record SourceOwnerEvidenceReceipt(
        long receiptId,
        SourceOwnerEvidence evidence,
        String authenticatedSourceService,
        Instant receivedAt,
        String evidenceSha256,
        SourceOwnerEvidenceOutcome outcome,
        String rejectionCode) {
}
