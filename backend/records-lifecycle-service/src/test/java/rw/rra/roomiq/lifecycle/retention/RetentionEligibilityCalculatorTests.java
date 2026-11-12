package rw.rra.roomiq.lifecycle.retention;

import org.junit.jupiter.api.Test;
import rw.rra.roomiq.lifecycle.policy.PolicyRuleSource;
import rw.rra.roomiq.lifecycle.policy.PolicyStatus;
import rw.rra.roomiq.lifecycle.policy.RetentionDurationUnit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionEligibilityCalculatorTests {
    private static final UUID POLICY_ID = UUID.randomUUID();
    private static final Instant POLICY_FROM = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant POLICY_TO = Instant.parse("2030-01-01T00:00:00Z");
    private final RetentionEligibilityCalculator calculator = new RetentionEligibilityCalculator();

    @Test
    void appliesEffectiveFromInclusively() {
        RetentionEvaluationResult result = calculator.evaluate(input(
                POLICY_FROM, Instant.parse("2026-01-01T00:00:00Z"),
                List.of(version(POLICY_FROM, POLICY_TO)), List.of(rule("ACCESS", 1, "DAY")),
                Map.of("ORIGINAL_EVENT", Instant.parse("2020-01-01T00:00:00Z")), List.of()));

        assertThat(result.eligible()).isTrue();
    }

    @Test
    void excludesEffectiveToBoundary() {
        RetentionEvaluationResult result = calculator.evaluate(input(
                POLICY_TO, Instant.parse("2040-01-01T00:00:00Z"),
                List.of(version(POLICY_FROM, POLICY_TO)), List.of(rule("ACCESS", 1, "DAY")),
                Map.of("ORIGINAL_EVENT", Instant.parse("2020-01-01T00:00:00Z")), List.of()));

        assertThat(result.reason()).isEqualTo(RetentionEvaluationResult.Reason.NO_APPLICABLE_POLICY);
    }

    @Test
    void usesLongestApplicableRuleAndExpiryEqualityIsEligible() {
        Instant start = Instant.parse("2020-03-10T12:00:00Z");
        RetentionEvaluationResult result = calculator.evaluate(input(
                start, Instant.parse("2025-03-10T12:00:00Z"),
                List.of(version(POLICY_FROM, POLICY_TO)),
                List.of(rule("SHORT", 2, "YEAR"), rule("LONG", 5, "YEAR")),
                Map.of("ORIGINAL_EVENT", start), List.of()));

        assertThat(result.eligible()).isTrue();
        assertThat(result.retainUntil()).isEqualTo(Instant.parse("2025-03-10T12:00:00Z"));
        assertThat(result.applicableRuleCodes()).containsExactlyInAnyOrder("SHORT", "LONG");
    }

    @Test
    void calculatesCalendarYearExpiryAndClampsLeapDayToMonthEnd() {
        Instant leapDay = Instant.parse("2024-02-29T10:00:00Z");
        RetentionEvaluationResult result = calculator.evaluate(input(
                leapDay, Instant.parse("2029-02-28T10:00:00Z"),
                List.of(version(POLICY_FROM, POLICY_TO)), List.of(rule("LEAP", 5, "YEAR")),
                Map.of("ORIGINAL_EVENT", leapDay), List.of()));

        assertThat(result.retainUntil()).isEqualTo(Instant.parse("2029-02-28T10:00:00Z"));
        assertThat(result.eligible()).isTrue();
    }

    @Test
    void calculatesFixedDaysInUtc() {
        Instant start = Instant.parse("2024-03-09T12:00:00Z");
        RetentionEvaluationResult result = calculator.evaluate(input(
                start, Instant.parse("2024-03-11T12:00:00Z"),
                List.of(version(POLICY_FROM, POLICY_TO)), List.of(rule("DAYS", 2, "DAY")),
                Map.of("ORIGINAL_EVENT", start), List.of()));

        assertThat(result.retainUntil()).isEqualTo(Instant.parse("2024-03-11T12:00:00Z"));
        assertThat(result.eligible()).isTrue();
    }

    @Test
    void usesLaterDecisionOrClosureTimestampForAiRule() {
        Instant closure = Instant.parse("2022-06-15T17:00:00Z");
        RetentionRuleSnapshot aiRule = new RetentionRuleSnapshot(POLICY_ID, "AI", "ACCESS_AUTHENTICATION",
                PolicyRuleSource.RRA_APPROVED, (short) 5, RetentionDurationUnit.YEAR,
                "LATER_DECISION_CLOSURE", null);
        RetentionEvaluationResult result = calculator.evaluate(input(
                POLICY_FROM, Instant.parse("2027-06-15T17:00:00Z"),
                List.of(version(POLICY_FROM, POLICY_TO)), List.of(aiRule),
                Map.of("DECISION_DATE", Instant.parse("2022-01-10T09:00:00Z"),
                        "TRANSACTION_CLOSURE", closure), List.of()));

        assertThat(result.retainUntil()).isEqualTo(Instant.parse("2027-06-15T17:00:00Z"));
        assertThat(result.eligible()).isTrue();
    }

    @Test
    void missingAiClosureFailsClosed() {
        RetentionRuleSnapshot aiRule = new RetentionRuleSnapshot(POLICY_ID, "AI", "ACCESS_AUTHENTICATION",
                PolicyRuleSource.RRA_APPROVED, (short) 5, RetentionDurationUnit.YEAR,
                "LATER_DECISION_CLOSURE", null);
        RetentionEvaluationResult result = calculator.evaluate(input(
                POLICY_FROM, Instant.parse("2040-01-01T00:00:00Z"),
                List.of(version(POLICY_FROM, POLICY_TO)), List.of(aiRule),
                Map.of("DECISION_DATE", POLICY_FROM), List.of()));

        assertThat(result.reason()).isEqualTo(RetentionEvaluationResult.Reason.RULE_START_MISSING);
    }

    @Test
    void statutoryRuleWithoutMatchingExternalResultFailsClosed() {
        RetentionRuleSnapshot statutory = new RetentionRuleSnapshot(POLICY_ID, "TAX", "ACCESS_AUTHENTICATION",
                PolicyRuleSource.STATUTORY, null, null, "EXTERNAL_SCHEDULE", "RRA-SCHEDULE-1");
        RetentionEvaluationResult result = calculator.evaluate(input(
                POLICY_FROM, Instant.parse("2040-01-01T00:00:00Z"),
                List.of(version(POLICY_FROM, POLICY_TO)), List.of(statutory),
                Map.of(), List.of()));

        assertThat(result.reason()).isEqualTo(
                RetentionEvaluationResult.Reason.SCHEDULE_RESULT_MISSING_OR_CONFLICTING);
    }

    @Test
    void conflictingEffectivePolicyVersionsFailClosed() {
        RetentionEvaluationResult result = calculator.evaluate(input(
                POLICY_FROM, Instant.parse("2040-01-01T00:00:00Z"),
                List.of(version(POLICY_FROM, null), version(POLICY_FROM, POLICY_TO)),
                List.of(rule("ACCESS", 1, "DAY")),
                Map.of("ORIGINAL_EVENT", POLICY_FROM), List.of()));

        assertThat(result.reason()).isEqualTo(RetentionEvaluationResult.Reason.CONFLICTING_POLICIES);
    }

    @Test
    void supersededVersionRemainsApplicableToRecordsInItsEffectivePeriod() {
        PolicyVersionSnapshot superseded = new PolicyVersionSnapshot(
                POLICY_ID, "policy-1", PolicyStatus.SUPERSEDED, POLICY_FROM, POLICY_TO);
        RetentionEvaluationResult result = calculator.evaluate(input(
                Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2040-01-01T00:00:00Z"),
                List.of(superseded), List.of(rule("ACCESS", 1, "DAY")),
                Map.of("ORIGINAL_EVENT", POLICY_FROM), List.of()));

        assertThat(result.eligible()).isTrue();
        assertThat(result.policyVersionId()).isEqualTo(POLICY_ID);
    }

    @Test
    void withdrawnVersionFailsClosed() {
        PolicyVersionSnapshot withdrawn = new PolicyVersionSnapshot(
        POLICY_ID, "withdrawn", PolicyStatus.WITHDRAWN, POLICY_FROM, POLICY_TO);
        RetentionEvaluationResult result = calculator.evaluate(input(
        POLICY_FROM, Instant.parse("2040-01-01T00:00:00Z"),
        List.of(withdrawn), List.of(rule("ACCESS", 1, "DAY")),
        Map.of("ORIGINAL_EVENT", POLICY_FROM), List.of()));

        assertThat(result.reason()).isEqualTo(RetentionEvaluationResult.Reason.POLICY_NOT_APPROVED);
    }

    @Test
    void withdrawnOverlappingVersionDoesNotConflictWithAnApplicableApprovedVersion() {
        PolicyVersionSnapshot withdrawn = new PolicyVersionSnapshot(
                UUID.randomUUID(), "withdrawn", PolicyStatus.WITHDRAWN, POLICY_FROM, POLICY_TO);
        RetentionEvaluationResult result = calculator.evaluate(input(
                POLICY_FROM, Instant.parse("2040-01-01T00:00:00Z"),
                List.of(version(POLICY_FROM, POLICY_TO), withdrawn), List.of(rule("ACCESS", 1, "DAY")),
                Map.of("ORIGINAL_EVENT", POLICY_FROM), List.of()));

        assertThat(result.eligible()).isTrue();
        assertThat(result.policyVersionId()).isEqualTo(POLICY_ID);
    }

    @Test
    void futureEffectivePolicyCannotBeSelected() {
        Instant futureEffectiveFrom = Instant.parse("2028-01-01T00:00:00Z");
        RetentionEvaluationResult result = calculator.evaluate(input(
                futureEffectiveFrom.minusNanos(1), Instant.parse("2040-01-01T00:00:00Z"),
                List.of(version(futureEffectiveFrom, null)), List.of(rule("ACCESS", 1, "DAY")),
                Map.of("ORIGINAL_EVENT", futureEffectiveFrom.minusNanos(1)), List.of()));

        assertThat(result.reason()).isEqualTo(RetentionEvaluationResult.Reason.NO_APPLICABLE_POLICY);
    }

    @Test
    void unknownCategoryAndMissingAuthoritativeTimestampFailClosed() {
        assertThat(calculator.evaluate(input(
                POLICY_FROM, Instant.parse("2040-01-01T00:00:00Z"), List.of(), List.of(), Map.of(), List.of()))
                .reason()).isEqualTo(RetentionEvaluationResult.Reason.NO_APPLICABLE_POLICY);

        RetentionEvaluationInput missingCategory = new RetentionEvaluationInput(
                true, true, " ", POLICY_FROM, Instant.parse("2040-01-01T00:00:00Z"),
                List.of(), List.of(), Map.of(), Map.of(), true, List.of());
        assertThat(calculator.evaluate(missingCategory).reason())
                .isEqualTo(RetentionEvaluationResult.Reason.CATEGORY_UNKNOWN);

        RetentionEvaluationInput missingStart = new RetentionEvaluationInput(
                true, true, "ACCESS", null, Instant.parse("2040-01-01T00:00:00Z"),
                List.of(), List.of(), Map.of(), Map.of(), true, List.of());
        assertThat(calculator.evaluate(missingStart).reason())
                .isEqualTo(RetentionEvaluationResult.Reason.AUTHORITATIVE_START_MISSING);
    }

    @Test
    void activePendingConflictedOrUnacknowledgedHoldBlocksEligibility() {
        for (HoldStatus hold : List.of(HoldStatus.ACTIVE, HoldStatus.PENDING_BLOCK, HoldStatus.CONFLICTED,
                HoldStatus.UNACKNOWLEDGED, HoldStatus.UNKNOWN, HoldStatus.RELEASE_PENDING)) {
            RetentionEvaluationResult result = calculator.evaluate(input(
                    POLICY_FROM, Instant.parse("2040-01-01T00:00:00Z"),
                    List.of(version(POLICY_FROM, POLICY_TO)), List.of(rule("ACCESS", 1, "DAY")),
                    Map.of("ORIGINAL_EVENT", POLICY_FROM), List.of(hold)));

            assertThat(result.reason()).isEqualTo(RetentionEvaluationResult.Reason.HOLD_BLOCKS_DISPOSAL);
        }
    }

    @Test
    void unavailableHoldStateAndUnacknowledgedOwnerFailClosed() {
        RetentionEvaluationInput unavailableHolds = new RetentionEvaluationInput(
                true, true, "ACCESS_AUTHENTICATION", POLICY_FROM, Instant.parse("2040-01-01T00:00:00Z"),
                List.of(version(POLICY_FROM, POLICY_TO)), List.of(rule("ACCESS", 1, "DAY")),
                Map.of("ORIGINAL_EVENT", POLICY_FROM), Map.of(), false, List.of());
        assertThat(calculator.evaluate(unavailableHolds).reason())
                .isEqualTo(RetentionEvaluationResult.Reason.HOLD_STATE_UNAVAILABLE);

        RetentionEvaluationInput unacknowledgedOwner = new RetentionEvaluationInput(
                true, false, "ACCESS_AUTHENTICATION", POLICY_FROM, Instant.parse("2040-01-01T00:00:00Z"),
                List.of(version(POLICY_FROM, POLICY_TO)), List.of(rule("ACCESS", 1, "DAY")),
                Map.of("ORIGINAL_EVENT", POLICY_FROM), Map.of(), true, List.of());
        assertThat(calculator.evaluate(unacknowledgedOwner).reason())
                .isEqualTo(RetentionEvaluationResult.Reason.OWNER_UNACKNOWLEDGED);
    }

    @Test
    void eligibilityResultDoesNotImplyAuthorizationToDispose() {
        RetentionEvaluationResult result = calculator.evaluate(input(
                POLICY_FROM, Instant.parse("2040-01-01T00:00:00Z"),
                List.of(version(POLICY_FROM, POLICY_TO)), List.of(rule("ACCESS", 1, "DAY")),
                Map.of("ORIGINAL_EVENT", POLICY_FROM), List.of()));

        assertThat(result.eligible()).isTrue();
        assertThat(result.status()).isEqualTo(RetentionEvaluationResult.Status.ELIGIBLE);
        assertThat(result.reason()).isEqualTo(RetentionEvaluationResult.Reason.RETENTION_EXPIRED);
    }

    private static RetentionEvaluationInput input(
            Instant authoritativeStart, Instant evaluationAt,
            List<PolicyVersionSnapshot> versions, List<RetentionRuleSnapshot> rules,
            Map<String, Instant> timestamps, List<HoldStatus> holds) {
        return new RetentionEvaluationInput(true, true, "ACCESS_AUTHENTICATION", authoritativeStart,
                evaluationAt, versions, rules, timestamps, Map.of(), true, holds);
    }

    private static PolicyVersionSnapshot version(Instant from, Instant to) {
        return new PolicyVersionSnapshot(POLICY_ID, "policy-1", PolicyStatus.APPROVED, from, to);
    }

    private static RetentionRuleSnapshot rule(String code, int duration, String unit) {
        return new RetentionRuleSnapshot(POLICY_ID, code, "ACCESS_AUTHENTICATION",
                PolicyRuleSource.RRA_APPROVED, (short) duration, RetentionDurationUnit.valueOf(unit),
                "ORIGINAL_EVENT", null);
    }
}
