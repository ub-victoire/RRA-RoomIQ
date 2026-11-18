package rw.rra.roomiq.lifecycle.sourceevidence;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class SourceOwnerEvidenceValidator {
    private static final Pattern SOURCE_SERVICE = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final Pattern RECORD_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern AUDIT_ACTION = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,127}");
    private static final Pattern CATEGORY_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern START_BASIS = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
    private final Set<String> allowedBookingCategories;
    private final Set<String> allowedAuditMappings;

    @Autowired
    public SourceOwnerEvidenceValidator(
            @Value("${roomiq.lifecycle.source-evidence.booking-requests.allowed-categories:}")
            String allowedBookingCategories,
            @Value("${roomiq.lifecycle.source-evidence.audit-events.allowed-mappings:}")
            String allowedAuditMappings) {
        this.allowedBookingCategories = Arrays.stream(allowedBookingCategories.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.allowedAuditMappings = parseAuditMappings(allowedAuditMappings);
    }

    SourceOwnerEvidenceValidator(String allowedBookingCategories) {
        this(allowedBookingCategories, "");
    }

    SourceOwnerEvidenceValidator() {
        this("", "");
    }

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
        requireText(evidence.eventType(), "audit-service".equals(authenticatedSourceService)
                ? AUDIT_ACTION : RECORD_TYPE, "eventType");
        require(evidence.eventVersion() > 0, "eventVersion must be positive");
        require(evidence.sourceRevision() > 0, "sourceRevision must be positive");
        requireText(evidence.categoryCode(), CATEGORY_CODE, "categoryCode");
        require(evidence.recordCreatedAt() != null, "recordCreatedAt is required");
        require(evidence.observedAt() != null, "observedAt is required");
        require(!evidence.observedAt().isBefore(evidence.recordCreatedAt()),
                "observedAt cannot precede recordCreatedAt");
        requireOptionalText(evidence.currentLifecycleStatus(), 32, "currentLifecycleStatus");
        if ("booking-service".equals(evidence.sourceService())) {
            require("BOOKING_REQUEST".equals(evidence.recordType()),
                    "Booking evidence recordType must be BOOKING_REQUEST");
            require(isUuid(evidence.recordId()), "Booking recordId must be a UUID");
            require("BOOKING_REQUEST_STATUS_CHANGED".equals(evidence.eventType())
                            && evidence.eventVersion() == 1,
                    "Booking evidence event type/version is unsupported");
            require(allowedBookingCategories.contains(evidence.categoryCode()),
                    "Booking evidence categoryCode is not configured as supported");
            require(evidence.currentLifecycleStatus() != null
                            && switch (evidence.currentLifecycleStatus()) {
                        case "DRAFT", "PENDING_APPROVAL", "APPROVED", "REJECTED", "CANCELLED", "EXPIRED" -> true;
                        default -> false;
                    },
                    "currentLifecycleStatus is invalid for a Booking request");
            boolean closed = switch (evidence.currentLifecycleStatus()) {
                case "REJECTED", "CANCELLED", "EXPIRED" -> true;
                default -> false;
            };
            require(closed == (evidence.closedAt() != null),
                    "closedAt must match the Booking request lifecycle status");
        }
        if ("audit-service".equals(evidence.sourceService())) {
            require("AUDIT_EVENT".equals(evidence.recordType()),
                    "Audit evidence recordType must be AUDIT_EVENT");
            require(isUuid(evidence.recordId()) && evidence.recordId().equals(evidence.eventId().toString()),
                    "Audit recordId must match its eventId");
            require(evidence.eventVersion() == 1 && evidence.sourceRevision() == 1,
                    "Audit evidence event version and source revision must equal 1");
            require(allowedAuditMappings.contains(auditMappingKey(
                            evidence.eventType(), evidence.categoryCode(), evidence.startBasisCode())),
                    "Audit event, category and start-basis mapping is not configured as supported");
            require("RECORDED".equals(evidence.currentLifecycleStatus()),
                    "Audit currentLifecycleStatus must be RECORDED");
            require(evidence.closedAt() == null && evidence.decisionAt() == null
                            && evidence.transactionClosedAt() == null && evidence.actorSubject() == null
                            && evidence.causationId() == null && evidence.supersedesEventId() == null,
                    "Audit evidence contains unsupported lifecycle or personal provenance fields");
        }
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

    private static Set<String> parseAuditMappings(String mappingSpec) {
        if (mappingSpec == null || mappingSpec.isBlank()) {
            return Set.of();
        }
        Set<String> mappings = new java.util.HashSet<>();
        for (String item : mappingSpec.split(";", -1)) {
            String[] sides = item.trim().split("=", -1);
            String[] values = sides.length == 2 ? sides[1].split(":", -1) : new String[0];
            if (sides.length != 2 || !AUDIT_ACTION.matcher(sides[0].trim()).matches()
                    || values.length != 2 || !CATEGORY_CODE.matcher(values[0].trim()).matches()
                    || !START_BASIS.matcher(values[1].trim()).matches()
                    || !Set.of("OCCURRED_AT", "ORIGINAL_EVENT", "RECORD_CREATED_AT")
                    .contains(values[1].trim())) {
                return Set.of();
            }
            if (!mappings.add(auditMappingKey(sides[0].trim(), values[0].trim(), values[1].trim()))) {
                return Set.of();
            }
        }
        return Set.copyOf(mappings);
    }

    private static String auditMappingKey(String action, String category, String startBasis) {
        return action + "|" + category + "|" + startBasis;
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

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
