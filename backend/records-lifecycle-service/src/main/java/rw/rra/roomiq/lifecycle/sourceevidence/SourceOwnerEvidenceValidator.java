package rw.rra.roomiq.lifecycle.sourceevidence;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class SourceOwnerEvidenceValidator {
    private static final Pattern SOURCE_SERVICE = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final Pattern RECORD_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern CATEGORY_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern START_BASIS = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    public SourceOwnerEvidence validate(SourceOwnerEvidence evidence, String authenticatedSourceService) {
        require(evidence != null, "Evidence is required");
        require(evidence.contractVersion() == SourceOwnerEvidence.SUPPORTED_CONTRACT_VERSION,
                "Unsupported evidence contract version");
        requireText(authenticatedSourceService, SOURCE_SERVICE, "authenticated source");
        requireText(evidence.sourceService(), SOURCE_SERVICE, "sourceService");
        require(Objects.equals(evidence.sourceService(), authenticatedSourceService),
                "sourceService must match the authenticated source");
        requireText(evidence.recordType(), RECORD_TYPE, "recordType");
        requireText(evidence.recordId(), 128, "recordId");
        require(evidence.eventId() != null, "eventId is required");
        requireText(evidence.eventType(), RECORD_TYPE, "eventType");
        require(evidence.eventVersion() > 0, "eventVersion must be positive");
        require(evidence.sourceRevision() > 0, "sourceRevision must be positive");
        requireText(evidence.categoryCode(), CATEGORY_CODE, "categoryCode");
        require(evidence.observedAt() != null, "observedAt is required");
        require(!evidence.observedAt().isBefore(evidence.recordCreatedAt()),
                "observedAt cannot precede recordCreatedAt");
        requireOptionalText(evidence.currentLifecycleStatus(), 32, "currentLifecycleStatus");
        if ("booking-service".equals(evidence.sourceService())) {
            require("BOOKING_REQUEST".equals(evidence.recordType()),
                    "Booking evidence recordType must be BOOKING_REQUEST");
            require(evidence.currentLifecycleStatus() != null
                            && switch (evidence.currentLifecycleStatus()) {
                        case "DRAFT", "PENDING_APPROVAL", "APPROVED", "REJECTED", "CANCELLED", "EXPIRED" -> true;
                        default -> false;
                    },
                    "currentLifecycleStatus is invalid for a Booking request");
        }
        require(evidence.recordCreatedAt() != null, "recordCreatedAt is required");
        require(evidence.occurredAt() != null, "occurredAt is required");
        require(evidence.authoritativeStartAt() != null, "authoritativeStartAt is required");
        requireText(evidence.startBasisCode(), START_BASIS, "startBasisCode");
        require(evidence.closedAt() == null || !evidence.closedAt().isBefore(evidence.recordCreatedAt()),
                "closedAt cannot precede recordCreatedAt");
        require(evidence.decisionAt() == null || !evidence.decisionAt().isBefore(evidence.recordCreatedAt()),
                "decisionAt cannot precede recordCreatedAt");
        require(evidence.transactionClosedAt() == null
                        || !evidence.transactionClosedAt().isBefore(evidence.recordCreatedAt()),
                "transactionClosedAt cannot precede recordCreatedAt");
        requireOptionalText(evidence.actorSubject(), 128, "actorSubject");
        requireOptionalText(evidence.correlationId(), 128, "correlationId");
        requireOptionalText(evidence.causationId(), 128, "causationId");
        validateStartBasis(evidence);
        return evidence;
    }

    private static void validateStartBasis(SourceOwnerEvidence evidence) {
        Instant expectedStart = switch (evidence.startBasisCode()) {
            case "RECORD_CREATED_AT" -> evidence.recordCreatedAt();
            case "OCCURRED_AT", "ORIGINAL_EVENT" -> evidence.occurredAt();
            case "CLOSED_AT" -> evidence.closedAt();
            case "LATER_DECISION_CLOSURE" -> {
                require(evidence.decisionAt() != null && evidence.transactionClosedAt() != null,
                        "decisionAt and transactionClosedAt are required for LATER_DECISION_CLOSURE");
                yield evidence.decisionAt().isAfter(evidence.transactionClosedAt())
                        ? evidence.decisionAt() : evidence.transactionClosedAt();
            }
            default -> evidence.authoritativeStartAt();
        };
        require(expectedStart != null && expectedStart.equals(evidence.authoritativeStartAt()),
                "authoritativeStartAt does not match startBasisCode");
    }

    private static void requireText(String value, Pattern pattern, String field) {
        require(value != null && pattern.matcher(value).matches(), field + " is invalid or missing");
    }

    private static void requireText(String value, int maxLength, String field) {
        require(value != null && !value.isBlank() && value.length() <= maxLength,
                field + " is invalid or missing");
    }

    private static void requireOptionalText(String value, int maxLength, String field) {
        require(value == null || (!value.isBlank() && value.length() <= maxLength), field + " is invalid");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new SourceOwnerEvidenceValidationException(message);
        }
    }
}
