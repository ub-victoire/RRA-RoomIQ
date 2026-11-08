package rw.rra.roomiq.lifecycle.policy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyDefinitionValidatorTests {
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private final PolicyDefinitionValidator validator = new PolicyDefinitionValidator();

    @Test
    void acceptsDraftVersionWithOnlyRequiredDraftFields() {
        assertThatCode(() -> validator.validateVersion(draft())).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidEffectiveInterval() {
        PolicyVersionDefinition invalid = new PolicyVersionDefinition(
                UUID.randomUUID(), "v1", sha256(), null, null,
                Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, null, PolicyStatus.DRAFT, CREATED_AT);

        assertThatThrownBy(() -> validator.validateVersion(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Effective-to");
    }

    @Test
    void rejectsApprovedVersionWithoutApprovalEvidence() {
        PolicyVersionDefinition invalid = new PolicyVersionDefinition(
                UUID.randomUUID(), "v1", sha256(), null, null, null, null,
                null, null, null, null, null, PolicyStatus.APPROVED, CREATED_AT);

        assertThatThrownBy(() -> validator.validateVersion(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Approval reference");
    }

    @Test
    void rejectsApprovedVersionWithoutLegalAndRecordsValidationEvidence() {
        PolicyVersionDefinition invalid = new PolicyVersionDefinition(
                UUID.randomUUID(), "v1", sha256(), "approval-ref", "policy-1",
                Instant.parse("2026-01-01T00:00:00Z"), null, "authority-ref", "approver",
                CREATED_AT, null, null, PolicyStatus.APPROVED, CREATED_AT);

        assertThatThrownBy(() -> validator.validateVersion(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Legal validation reference");
    }

    @Test
    void rejectsNonSha256DocumentDigest() {
        PolicyVersionDefinition invalid = new PolicyVersionDefinition(
                UUID.randomUUID(), "v1", "not-a-digest", null, null, null, null,
                null, null, null, null, null, PolicyStatus.DRAFT, CREATED_AT);

        assertThatThrownBy(() -> validator.validateVersion(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void acceptsPositiveExplicitDuration() {
        PolicyRuleDefinition rule = new PolicyRuleDefinition(
                UUID.randomUUID(), UUID.randomUUID(), "ACCESS", "ACCESS_AUTHENTICATION",
                PolicyRuleSource.RRA_PROPOSED, (short) 2, RetentionDurationUnit.YEAR,
                "ORIGINAL_EVENT", null, CREATED_AT);

        assertThatCode(() -> validator.validateRule(rule)).doesNotThrowAnyException();
    }

    @Test
    void requiresAnExternalReferenceWhenDurationIsAbsent() {
        PolicyRuleDefinition rule = new PolicyRuleDefinition(
                UUID.randomUUID(), UUID.randomUUID(), "TAX", "FINANCIAL_TAX",
                PolicyRuleSource.STATUTORY, null, null, "EXTERNAL_SCHEDULE", null, CREATED_AT);

        assertThatThrownBy(() -> validator.validateRule(rule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schedule reference");
    }

    @Test
    void rejectsNonPositiveDuration() {
        PolicyRuleDefinition rule = new PolicyRuleDefinition(
                UUID.randomUUID(), UUID.randomUUID(), "ACCESS", "ACCESS_AUTHENTICATION",
                PolicyRuleSource.RRA_PROPOSED, (short) 0, RetentionDurationUnit.YEAR,
                "ORIGINAL_EVENT", null, CREATED_AT);

        assertThatThrownBy(() -> validator.validateRule(rule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    private static PolicyVersionDefinition draft() {
        return new PolicyVersionDefinition(
                UUID.randomUUID(), "draft-v1", sha256(), null, null, null, null,
                null, null, null, null, null, PolicyStatus.DRAFT, CREATED_AT);
    }

    private static String sha256() {
        return "a".repeat(64);
    }
}
