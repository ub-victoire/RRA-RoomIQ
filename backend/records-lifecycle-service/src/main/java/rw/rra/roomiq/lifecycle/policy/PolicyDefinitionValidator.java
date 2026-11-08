package rw.rra.roomiq.lifecycle.policy;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class PolicyDefinitionValidator {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    public void validateVersion(PolicyVersionDefinition definition) {
        require(definition != null, "Policy version is required");
        require(definition.policyVersionId() != null, "Policy version ID is required");
        requireText(definition.versionLabel(), 64, "Version label");
        require(definition.documentSha256() != null && SHA_256.matcher(definition.documentSha256()).matches(),
                "Document SHA-256 must contain 64 hexadecimal characters");
        require(definition.status() != null, "Policy status is required");
        require(definition.createdAt() != null, "Policy creation timestamp is required");
        validateOptionalText(definition.approvalReference(), 128, "Approval reference");
        validateOptionalText(definition.policyVersion(), 64, "Policy version");
        validateOptionalText(definition.approvingAuthorityRef(), 128, "Approving authority reference");
        validateOptionalText(definition.approvedBySubject(), 128, "Approver subject");
        validateOptionalText(definition.legalValidationRef(), 128, "Legal validation reference");
        validateOptionalText(definition.recordsValidationRef(), 128, "Records validation reference");

        if (definition.effectiveTo() != null) {
            require(definition.effectiveFrom() != null
                            && definition.effectiveTo().isAfter(definition.effectiveFrom()),
                    "Effective-to must be later than effective-from");
        }

        if (definition.status() != PolicyStatus.DRAFT) {
            requireText(definition.approvalReference(), 128, "Approval reference");
            requireText(definition.policyVersion(), 64, "Policy version");
            require(definition.effectiveFrom() != null, "Effective-from is required for an approved policy");
            requireText(definition.approvingAuthorityRef(), 128, "Approving authority reference");
            requireText(definition.approvedBySubject(), 128, "Approver subject");
            require(definition.approvedAt() != null, "Approval timestamp is required");
            requireText(definition.legalValidationRef(), 128, "Legal validation reference");
            requireText(definition.recordsValidationRef(), 128, "Records validation reference");
        }
    }

    public void validateRule(PolicyRuleDefinition definition) {
        require(definition != null, "Policy rule is required");
        require(definition.ruleId() != null, "Rule ID is required");
        require(definition.policyVersionId() != null, "Policy version ID is required");
        requireText(definition.ruleCode(), 64, "Rule code");
        requireText(definition.categoryCode(), 64, "Category code");
        require(definition.ruleSource() != null, "Rule source is required");
        requireText(definition.startBasisCode(), 40, "Start basis code");
        require(definition.createdAt() != null, "Rule creation timestamp is required");
        validateOptionalText(definition.scheduleReference(), 256, "Schedule reference");

        boolean hasDuration = definition.durationValue() != null;
        boolean hasUnit = definition.durationUnit() != null;
        require(hasDuration == hasUnit, "Duration value and unit must both be present or both be absent");
        if (hasDuration) {
            require(definition.durationValue() > 0, "Duration value must be positive");
            require(definition.scheduleReference() == null,
                    "An explicit duration cannot include an external schedule reference");
        } else {
            requireText(definition.scheduleReference(), 256,
                    "A schedule reference is required when duration is external");
        }
        if (definition.ruleSource() == PolicyRuleSource.STATUTORY) {
            requireText(definition.scheduleReference(), 256,
                    "A statutory rule requires an external schedule reference");
        }
    }

    private static void validateOptionalText(String value, int maxLength, String name) {
        require(value == null || value.length() <= maxLength, name + " exceeds " + maxLength + " characters");
    }

    private static void requireText(String value, int maxLength, String name) {
        require(value != null && !value.isBlank(), name + " is required");
        require(value.length() <= maxLength, name + " exceeds " + maxLength + " characters");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
