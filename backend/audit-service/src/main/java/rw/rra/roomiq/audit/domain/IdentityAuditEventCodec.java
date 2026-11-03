package rw.rra.roomiq.audit.domain;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class IdentityAuditEventCodec {
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "eventId", "schemaVersion", "eventType", "timestamp", "sourceService", "actorUserId",
            "resourceType", "resourceId", "scopeOfficeBuildingId", "outcome", "correlationId", "metadata");
    private static final Pattern CORRELATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern PRIVILEGE_CODE = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,63}");
    private static final Map<String, EventDefinition> EVENTS = Map.ofEntries(
            Map.entry("AUTHENTICATION_SUCCEEDED", new EventDefinition("USER", Set.of(), false, false, "SUCCESS")),
            Map.entry("AUTHENTICATION_FAILED", new EventDefinition("AUTHENTICATION", Set.of(), true, true, "DENIED")),
            Map.entry("REFRESH_TOKEN_ROTATED", new EventDefinition("USER", Set.of(), false, false, "SUCCESS")),
            Map.entry("REFRESH_TOKEN_REJECTED", new EventDefinition("AUTHENTICATION", Set.of(), true, true, "DENIED")),
            Map.entry("AUTHENTICATION_LOGOUT", new EventDefinition("USER_SESSION", Set.of(), false, false, "SUCCESS")),
            Map.entry("AUTHORIZATION_DENIED", new EventDefinition("API", Set.of(), true, true, "DENIED")),
            Map.entry("IDENTITY_USER_CREATED", new EventDefinition("USER", Set.of(), false, false, "SUCCESS")),
            Map.entry("IDENTITY_USER_UPDATED", new EventDefinition("USER", Set.of(), false, false, "SUCCESS")),
            Map.entry("IDENTITY_USER_STATUS_CHANGED", new EventDefinition("USER", Set.of(), false, false, "SUCCESS")),
            Map.entry("IDENTITY_USER_SOFT_DELETED", new EventDefinition("USER", Set.of(), false, false, "SUCCESS")),
            Map.entry("IDENTITY_ROLE_CREATED", new EventDefinition("ROLE", Set.of(), false, false, "SUCCESS")),
            Map.entry("IDENTITY_PERMISSION_CREATED", new EventDefinition("PERMISSION", Set.of(), false, false, "SUCCESS")),
            Map.entry("IDENTITY_ROLE_PERMISSION_GRANTED", new EventDefinition("ROLE_PERMISSION", Set.of(),
                    false, false, "SUCCESS")),
            Map.entry("IDENTITY_ROLE_PERMISSION_REVOKED", new EventDefinition("ROLE", Set.of("permissionId"),
                    false, false, "SUCCESS")),
            Map.entry("IDENTITY_USER_ROLE_ASSIGNED", new EventDefinition("USER_ROLE", Set.of(),
                    false, false, "SUCCESS")),
            Map.entry("IDENTITY_USER_ROLE_REVOKED", new EventDefinition("USER_ROLE", Set.of(),
                    false, false, "SUCCESS")),
            Map.entry("IDENTITY_PRIVILEGE_GRANTED", new EventDefinition("USER_PRIVILEGE", Set.of("privilegeCode"),
                    false, false, "SUCCESS")),
            Map.entry("IDENTITY_PRIVILEGE_REVOKED", new EventDefinition("USER_PRIVILEGE", Set.of("privilegeCode"),
                    false, false, "SUCCESS")));

    private final ObjectMapper objectMapper;

    public IdentityAuditEventCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static Set<String> supportedEventTypes() {
        return EVENTS.keySet();
    }

    public IdentityAuditEvent decode(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > 65_536) {
            throw invalid("Identity audit event body has an invalid size");
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.isObject() || !hasOnlyFields(root, ENVELOPE_FIELDS)) {
                throw invalid("Identity audit event envelope is malformed");
            }
            UUID eventId = requiredUuid(root, "eventId");
            int schemaVersion = requiredInteger(root, "schemaVersion");
            String eventType = requiredText(root, "eventType", 96);
            if (schemaVersion != 1) {
                throw invalid("Identity audit event schema version is unsupported");
            }
            EventDefinition definition = EVENTS.get(eventType);
            if (definition == null) {
                throw invalid("Identity audit event type is unsupported");
            }
            if (!"identity-service".equals(requiredText(root, "sourceService", 64))) {
                throw invalid("Identity audit event source is invalid");
            }

            UUID actorUserId = optionalUuid(root, "actorUserId");
            UUID resourceId = optionalUuid(root, "resourceId");
            UUID scopeOfficeBuildingId = optionalUuid(root, "scopeOfficeBuildingId");
            if ((!definition.actorMayBeNull() && actorUserId == null)
                    || (!definition.resourceIdMayBeNull() && resourceId == null)
                    || !definition.resourceType().equals(requiredText(root, "resourceType", 64))) {
                throw invalid("Identity audit event resource facts are incomplete or inconsistent");
            }
            String outcome = requiredText(root, "outcome", 32);
            if (!definition.outcome().equals(outcome)) {
                throw invalid("Identity audit event outcome is inconsistent with its event type");
            }
            String correlationId = optionalText(root, "correlationId", 128);
            if (correlationId != null && !CORRELATION_ID.matcher(correlationId).matches()) {
                throw invalid("Identity audit event correlation identifier is invalid");
            }
            Instant timestamp;
            try {
                timestamp = Instant.parse(requiredText(root, "timestamp", 64));
            } catch (DateTimeParseException exception) {
                throw invalid("Identity audit event timestamp is invalid");
            }

            JsonNode eventMetadata = root.get("metadata");
            validateMetadata(eventMetadata, definition.metadataFields());
            ObjectNode storedMetadata = objectMapper.createObjectNode();
            storedMetadata.put("sourceService", "identity-service");
            storedMetadata.put("schemaVersion", schemaVersion);
            storedMetadata.set("eventMetadata", eventMetadata.deepCopy());

            return new IdentityAuditEvent(eventId, schemaVersion, eventType, timestamp, actorUserId,
                    definition.resourceType(), resourceId, scopeOfficeBuildingId, outcome, correlationId,
                    objectMapper.writeValueAsString(storedMetadata));
        } catch (JacksonException exception) {
            throw invalid("Identity audit event JSON is malformed");
        }
    }

    private static void validateMetadata(JsonNode metadata, Set<String> allowedFields) {
        if (metadata == null || !metadata.isObject() || !hasOnlyFields(metadata, allowedFields)
                || metadata.size() != allowedFields.size()) {
            throw invalid("Identity audit event metadata is malformed");
        }
        if (metadata.has("permissionId")) {
            parseUuid(metadata.get("permissionId"), "permissionId");
        }
        if (metadata.has("privilegeCode")) {
            JsonNode privilegeCode = metadata.get("privilegeCode");
            if (!privilegeCode.isTextual() || !PRIVILEGE_CODE.matcher(privilegeCode.asText()).matches()) {
                throw invalid("Identity audit event privilege code is invalid");
            }
        }
    }

    private static boolean hasOnlyFields(JsonNode node, Set<String> allowedFields) {
        for (var entry : node.properties()) {
            if (!allowedFields.contains(entry.getKey())) {
                return false;
            }
        }
        return true;
    }

    private static String requiredText(JsonNode node, String name, int maximumLength) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maximumLength) {
            throw invalid("Identity audit event field is invalid: " + name);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String name, int maximumLength) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().length() > maximumLength) {
            throw invalid("Identity audit event field is invalid: " + name);
        }
        return value.asText();
    }

    private static int requiredInteger(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid("Identity audit event field is invalid: " + name);
        }
        return value.intValue();
    }

    private static UUID requiredUuid(JsonNode node, String name) {
        UUID value = optionalUuid(node, name);
        if (value == null) {
            throw invalid("Identity audit event field is invalid: " + name);
        }
        return value;
    }

    private static UUID optionalUuid(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        return parseUuid(value, name);
    }

    private static UUID parseUuid(JsonNode value, String name) {
        if (!value.isTextual() || value.asText().length() != 36) {
            throw invalid("Identity audit event field is invalid: " + name);
        }
        try {
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException exception) {
            throw invalid("Identity audit event field is invalid: " + name);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private record EventDefinition(String resourceType, Set<String> metadataFields, boolean actorMayBeNull,
                                   boolean resourceIdMayBeNull, String outcome) {
    }
}
