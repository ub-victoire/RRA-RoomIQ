package rw.rra.roomiq.lifecycle.retention;

import java.time.Instant;
import java.util.UUID;

public record SourceEvidenceEvaluationResult(
        UUID sourceEvidenceEventId,
        long sourceRevision,
        String sourceEvidenceSha256,
        Instant evidenceReceivedAt,
        RetentionEvaluationResult evaluation) {
}
