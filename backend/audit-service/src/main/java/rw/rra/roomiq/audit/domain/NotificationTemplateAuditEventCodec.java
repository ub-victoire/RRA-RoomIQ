package rw.rra.roomiq.audit.domain;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class NotificationTemplateAuditEventCodec {
    private static final Set<String> EVENT_TYPES = Set.of(
            "NotificationTemplateCreated", "NotificationTemplateActivated",
            "NotificationTemplateDeactivated");
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "eventId", "schemaVersion", "eventType", "timestamp", "sourceService", "actorUserId",
            "resourceType", "resourceId", "correlationId", "metadata");
    private static final Set<String> METADATA_FIELDS = Set.of("code", "version", "channel", "changedFields");
    private static final Set<String> AUDITABLE_FIELDS = Set.of(
            "code", "version", "channel", "subject", "bodyTemplate", "isActive");
    private static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,127}");
    private static final Pattern CORRELATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final ObjectMapper objectMapper;

    public NotificationTemplateAuditEventCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NotificationTemplateAuditEvent decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw invalid("Audit event body is empty");
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.isObject() || !hasOnlyFields(root, ENVELOPE_FIELDS)) {
                throw invalid("Audit event envelope is malformed");
            }
            UUID eventId = uuid(root, "eventId");
            int schemaVersion = integer(root, "schemaVersion");
            String eventType = text(root, "eventType", 128);
            if (schemaVersion != 1 || !EVENT_TYPES.contains(eventType)) {
                throw invalid("Audit event schema version or event type is unsupported");
            }
            if (!"notification-service".equals(text(root, "sourceService", 64))
                    || !"NOTIFICATION_TEMPLATE".equals(text(root, "resourceType", 64))) {
                throw invalid("Audit event source or resource type is invalid");
            }
            UUID actorUserId = uuid(root, "actorUserId");
            UUID resourceId = uuid(root, "resourceId");
            String correlationId = optionalText(root, "correlationId", 128);
            if (correlationId != null && !CORRELATION_ID.matcher(correlationId).matches()) {
                throw invalid("Audit event correlation identifier is invalid");
            }
            Instant timestamp;
            try {
                timestamp = Instant.parse(text(root, "timestamp", 64));
            } catch (DateTimeParseException exception) {
                throw invalid("Audit event timestamp is invalid");
            }

            JsonNode metadata = root.get("metadata");
            validateMetadata(metadata);
            ObjectNode storedMetadata = objectMapper.createObjectNode();
            storedMetadata.put("sourceService", "notification-service");
            storedMetadata.put("schemaVersion", schemaVersion);
            storedMetadata.set("eventMetadata", metadata.deepCopy());
            return new NotificationTemplateAuditEvent(eventId, schemaVersion, eventType, timestamp,
                    "notification-service", actorUserId, "NOTIFICATION_TEMPLATE", resourceId, correlationId,
                    objectMapper.writeValueAsString(storedMetadata));
        } catch (JacksonException exception) {
            throw invalid("Audit event JSON is malformed");
        }
    }

    private static void validateMetadata(JsonNode metadata) {
        if (metadata == null || !metadata.isObject() || !hasOnlyFields(metadata, METADATA_FIELDS)) {
            throw invalid("Audit event metadata is malformed");
        }
        String code = text(metadata, "code", 128);
        if (!CODE.matcher(code).matches()) {
            throw invalid("Audit template code is invalid");
        }
        int version = integer(metadata, "version");
        String channel = text(metadata, "channel", 16);
        if (version < 1 || !Set.of("IN_APP", "EMAIL").contains(channel)) {
            throw invalid("Audit template version or channel is invalid");
        }
        JsonNode changedFields = metadata.get("changedFields");
        if (changedFields == null || !changedFields.isArray() || changedFields.isEmpty()) {
            throw invalid("Audit event changed fields are missing");
        }
        for (JsonNode field : changedFields) {
            if (!field.isTextual() || !AUDITABLE_FIELDS.contains(field.asText())) {
                throw invalid("Audit event contains an unsupported changed field");
            }
        }
    }

    private static boolean hasOnlyFields(JsonNode node, Set<String> allowedFields) {
        var names = node.properties();
        int count = 0;
        for (var entry : names) {
            if (!allowedFields.contains(entry.getKey())) {
                return false;
            }
            count++;
        }
        return count == node.size();
    }

    private static String text(JsonNode node, String name, int maximumLength) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maximumLength) {
            throw invalid("Audit event field is invalid: " + name);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String name, int maximumLength) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().length() > maximumLength) {
            throw invalid("Audit event field is invalid: " + name);
        }
        return value.asText();
    }

    private static int integer(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.canConvertToInt()) {
            throw invalid("Audit event field is invalid: " + name);
        }
        return value.intValue();
    }

    private static UUID uuid(JsonNode node, String name) {
        try {
            return UUID.fromString(text(node, name, 36));
        } catch (IllegalArgumentException exception) {
            throw invalid("Audit event field is invalid: " + name);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
