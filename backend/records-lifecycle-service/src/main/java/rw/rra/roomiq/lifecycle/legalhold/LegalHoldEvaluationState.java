package rw.rra.roomiq.lifecycle.legalhold;

import java.util.List;

public record LegalHoldEvaluationState(boolean available, List<ApplicableLegalHold> applicableHolds) {
    public LegalHoldEvaluationState {
        applicableHolds = applicableHolds == null ? List.of() : List.copyOf(applicableHolds);
    }

    public List<rw.rra.roomiq.lifecycle.retention.HoldStatus> statuses() {
        return applicableHolds.stream().map(ApplicableLegalHold::evaluationStatus).toList();
    }

    public static LegalHoldEvaluationState unavailable() {
        return new LegalHoldEvaluationState(false, List.of());
    }
}
