package rw.rra.roomiq.lifecycle.retention;

import org.springframework.stereotype.Component;
import rw.rra.roomiq.lifecycle.policy.PolicyRuleSource;
import rw.rra.roomiq.lifecycle.policy.PolicyStatus;
import rw.rra.roomiq.lifecycle.policy.RetentionDurationUnit;

import java.time.Instant;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
public class RetentionEligibilityCalculator {
    private static final String LATER_DECISION_CLOSURE = "LATER_DECISION_CLOSURE";
    private static final String DECISION_DATE = "DECISION_DATE";
    private static final String TRANSACTION_CLOSURE = "TRANSACTION_CLOSURE";

    public RetentionEvaluationResult evaluate(RetentionEvaluationInput input) {
        if (input == null || !input.ownerKnown()) {
            return notEligible(RetentionEvaluationResult.Reason.OWNER_UNKNOWN);
        }
        if (!input.ownerAcknowledged()) {
            return notEligible(RetentionEvaluationResult.Reason.OWNER_UNACKNOWLEDGED);
        }
        if (input.categoryCode() == null || input.categoryCode().isBlank()) {
            return notEligible(RetentionEvaluationResult.Reason.CATEGORY_UNKNOWN);
        }
        if (input.authoritativeStartAt() == null) {
            return notEligible(RetentionEvaluationResult.Reason.AUTHORITATIVE_START_MISSING);
        }
        if (input.evaluationAt() == null) {
            return notEligible(RetentionEvaluationResult.Reason.EVALUATION_TIME_MISSING);
        }

        List<PolicyVersionSnapshot> versions = safeVersions(input.policyVersions());
        List<PolicyVersionSnapshot> effectiveVersions = versions.stream()
                .filter(Objects::nonNull)
                .filter(version -> version.status() != null && version.status() != PolicyStatus.DRAFT)
                .filter(version -> isEffectiveAt(version, input.authoritativeStartAt()))
                .toList();
        boolean matchingDraft = versions.stream().filter(Objects::nonNull)
                .anyMatch(version -> version.status() == PolicyStatus.DRAFT
                        && isEffectiveAt(version, input.authoritativeStartAt()));

        if (effectiveVersions.size() > 1) {
            return notEligible(RetentionEvaluationResult.Reason.CONFLICTING_POLICIES);
        }
        if (effectiveVersions.isEmpty()) {
            return notEligible(matchingDraft
                    ? RetentionEvaluationResult.Reason.POLICY_NOT_APPROVED
                    : RetentionEvaluationResult.Reason.NO_APPLICABLE_POLICY);
        }

        PolicyVersionSnapshot version = effectiveVersions.getFirst();
        if (version.status() != PolicyStatus.APPROVED && version.status() != PolicyStatus.SUPERSEDED) {
            return notEligible(RetentionEvaluationResult.Reason.POLICY_NOT_APPROVED);
        }
        List<RetentionRuleSnapshot> applicableRules = safeRules(input.rules()).stream()
                .filter(Objects::nonNull)
                .filter(rule -> version.id().equals(rule.policyVersionId()))
                .filter(rule -> input.categoryCode().equals(rule.categoryCode()))
                .toList();
        if (applicableRules.isEmpty()) {
            return notEligible(RetentionEvaluationResult.Reason.NO_APPLICABLE_RULES);
        }

        List<Instant> expiries = new java.util.ArrayList<>(applicableRules.size());
        for (RetentionRuleSnapshot rule : applicableRules) {
            ExpiryCalculation calculation = calculateExpiry(rule, input);
            if (calculation.expiry() == null) {
                return notEligible(calculation.reason());
            }
            expiries.add(calculation.expiry());
        }
        Instant retainUntil = expiries.stream().max(Comparator.naturalOrder()).orElseThrow();

        if (!input.holdStateAvailable() || input.holdStatuses() == null) {
            return ineligible(RetentionEvaluationResult.Reason.HOLD_STATE_UNAVAILABLE, retainUntil, version,
                    applicableRules);
        }
        if (input.holdStatuses().stream().anyMatch(RetentionEligibilityCalculator::blocksDisposal)) {
            return ineligible(RetentionEvaluationResult.Reason.HOLD_BLOCKS_DISPOSAL, retainUntil, version,
                    applicableRules);
        }
        if (input.evaluationAt().isBefore(retainUntil)) {
            return ineligible(RetentionEvaluationResult.Reason.RETENTION_NOT_EXPIRED, retainUntil, version,
                    applicableRules);
        }
        return result(RetentionEvaluationResult.Status.ELIGIBLE, RetentionEvaluationResult.Reason.RETENTION_EXPIRED,
                retainUntil, version, applicableRules);
    }

    private static ExpiryCalculation calculateExpiry(RetentionRuleSnapshot rule, RetentionEvaluationInput input) {
        if (rule.ruleSource() == null || rule.startBasisCode() == null) {
            return new ExpiryCalculation(null, RetentionEvaluationResult.Reason.RULE_START_MISSING);
        }

        boolean externalSchedule = rule.durationValue() == null && rule.durationUnit() == null;
        if (rule.ruleSource() == PolicyRuleSource.STATUTORY && rule.scheduleReference() == null) {
            return new ExpiryCalculation(null,
                    RetentionEvaluationResult.Reason.SCHEDULE_RESULT_MISSING_OR_CONFLICTING);
        }
        if (externalSchedule) {
            ExternalScheduleResult result = input.externalScheduleResultsByRuleCode() == null
                    ? null : input.externalScheduleResultsByRuleCode().get(rule.ruleCode());
            if (result == null || result.expiryAt() == null
                    || !Objects.equals(rule.scheduleReference(), result.scheduleReference())) {
                return new ExpiryCalculation(null,
                        RetentionEvaluationResult.Reason.SCHEDULE_RESULT_MISSING_OR_CONFLICTING);
            }
            return new ExpiryCalculation(result.expiryAt(), null);
        }

        Instant start = resolveStart(rule.startBasisCode(), input.startTimestampsByBasis());
        if (start == null || rule.durationValue() == null || rule.durationValue() <= 0
                || rule.durationUnit() == null) {
            return new ExpiryCalculation(null, RetentionEvaluationResult.Reason.RULE_START_MISSING);
        }
        try {
            LocalDateTime utcStart = LocalDateTime.ofInstant(start, ZoneOffset.UTC);
            LocalDateTime utcExpiry = switch (rule.durationUnit()) {
                case YEAR -> utcStart.plusYears(rule.durationValue());
                case DAY -> utcStart.plusDays(rule.durationValue());
            };
            return new ExpiryCalculation(utcExpiry.toInstant(ZoneOffset.UTC), null);
        } catch (DateTimeException exception) {
            return new ExpiryCalculation(null,
                    RetentionEvaluationResult.Reason.RETENTION_EXPIRY_UNCOMPUTABLE);
        }
    }

    private static boolean isEffectiveAt(PolicyVersionSnapshot version, Instant instant) {
        return version.effectiveFrom() != null
                && !instant.isBefore(version.effectiveFrom())
                && (version.effectiveTo() == null || instant.isBefore(version.effectiveTo()));
    }

    private static Instant resolveStart(String startBasis, java.util.Map<String, Instant> starts) {
        if (starts == null) {
            return null;
        }
        if (LATER_DECISION_CLOSURE.equals(startBasis)) {
            Instant decision = starts.get(DECISION_DATE);
            Instant closure = starts.get(TRANSACTION_CLOSURE);
            return decision == null || closure == null ? null
                    : (decision.isAfter(closure) ? decision : closure);
        }
        if ("EXTERNAL_SCHEDULE".equals(startBasis)) {
            return null;
        }
        return starts.get(startBasis);
    }

    private static boolean blocksDisposal(HoldStatus status) {
        return status == null || switch (status) {
            case PENDING_BLOCK, ACTIVE, RELEASE_PENDING, CONFLICTED, UNACKNOWLEDGED, UNKNOWN -> true;
            case RELEASED, REJECTED -> false;
        };
    }

    private static List<RetentionRuleSnapshot> safeRules(List<RetentionRuleSnapshot> values) {
        return values == null ? List.of() : values;
    }

    private static List<PolicyVersionSnapshot> safeVersions(List<PolicyVersionSnapshot> values) {
        return values == null ? List.of() : values;
    }

    private record ExpiryCalculation(Instant expiry, RetentionEvaluationResult.Reason reason) {
    }

    private static RetentionEvaluationResult notEligible(RetentionEvaluationResult.Reason reason) {
        return new RetentionEvaluationResult(RetentionEvaluationResult.Status.NOT_ELIGIBLE, reason, null, null,
                List.of());
    }

    private static RetentionEvaluationResult ineligible(RetentionEvaluationResult.Reason reason, Instant retainUntil,
                                                         PolicyVersionSnapshot version,
                                                         List<RetentionRuleSnapshot> rules) {
        return result(RetentionEvaluationResult.Status.NOT_ELIGIBLE, reason, retainUntil, version, rules);
    }

    private static RetentionEvaluationResult result(RetentionEvaluationResult.Status status,
                                                    RetentionEvaluationResult.Reason reason, Instant retainUntil,
                                                    PolicyVersionSnapshot version,
                                                    List<RetentionRuleSnapshot> rules) {
        return new RetentionEvaluationResult(status, reason, retainUntil, version.id(),
                rules.stream().map(RetentionRuleSnapshot::ruleCode).toList());
    }
}
