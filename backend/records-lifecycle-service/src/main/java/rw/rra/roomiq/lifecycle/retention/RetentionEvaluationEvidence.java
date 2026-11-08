package rw.rra.roomiq.lifecycle.retention;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RetentionEvaluationEvidence(
        boolean ownerKnown,
        boolean ownerAcknowledged,
        String categoryCode,
        Instant authoritativeStartAt,
        Instant evaluationAt,
        Map<String, Instant> startTimestampsByBasis,
        Map<String, ExternalScheduleResult> externalScheduleResultsByRuleCode,
        boolean holdStateAvailable,
        List<HoldStatus> holdStatuses) {
}
