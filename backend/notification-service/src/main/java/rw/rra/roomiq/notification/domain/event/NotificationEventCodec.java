package rw.rra.roomiq.notification.domain.event;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class NotificationEventCodec {
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "eventId", "sourceService", "eventType", "eventVersion", "aggregateType", "aggregateId",
            "aggregateVersion", "correlationId", "causationId", "occurredAt", "payload");
    private static final Set<String> TIMESTAMP_PAYLOAD_FACTS = Set.of(
            "completedAt", "releasedAt", "reminderAt", "occurrenceStartsAt", "periodStart", "periodEnd");
    private static final Map<String, EventDefinition> EVENT_DEFINITIONS = Map.ofEntries(
            Map.entry("BookingRequested", new EventDefinition("booking-service", "booking.requested", "BOOKING_REQUEST",
                    Set.of("requesterUserId"))),
            Map.entry("BookingApproved", new EventDefinition("booking-service", "booking.approved", "BOOKING_REQUEST",
                    Set.of("requesterUserId", "decidedByUserId"))),
            Map.entry("BookingRejected", new EventDefinition("booking-service", "booking.rejected", "BOOKING_REQUEST",
                    Set.of("requesterUserId", "decidedByUserId"))),
            Map.entry("BookingCancelled", new EventDefinition("booking-service", "booking.cancelled", "CANCELLATION",
                    Set.of("cancelledByUserId", "reasonCode"))),
            Map.entry("ExtensionRequested", new EventDefinition("booking-service", "booking.extension.requested",
                    "BOOKING_EXTENSION",
                    Set.of("requesterUserId"))),
            Map.entry("ExtensionDecided", new EventDefinition("booking-service", "booking.extension.decided",
                    "BOOKING_EXTENSION",
                    Set.of("requesterUserId", "decidedByUserId", "decision"))),
            Map.entry("RoomStatusChanged", new EventDefinition("room-service", "room.status.changed", "ROOM",
                    Set.of("roomId", "status"))),
            Map.entry("RoomMaintenanceChanged", new EventDefinition("room-service", "room.maintenance.changed",
                    "ROOM",
                    Set.of("roomId", "maintenancePeriodId", "action", "periodStart", "periodEnd"))),
            Map.entry("MeetingCompleted", new EventDefinition("booking-service", "booking.meeting.completed", "MEETING",
                    Set.of("meetingId", "completedAt", "reservationId"))),
            Map.entry("MeetingReleased", new EventDefinition("booking-service", "booking.meeting.released", "MEETING",
                    Set.of("meetingId", "releasedAt", "releasedByUserId", "reservationId"))),
            Map.entry("Reminder", new EventDefinition("booking-service", "notification.reminder", "MEETING",
                    Set.of("meetingId", "reservationId", "occurrenceStartsAt", "timezone",
                            "reminderAt", "reminderOffsetMinutes"))),
            Map.entry("AutomaticReleaseOrNoShow", new EventDefinition("booking-service",
                    "booking.automatic-release-or-no-show", "RESERVATION",
                    Set.of("reservationId", "reasonCode"))));

    private final ObjectMapper objectMapper;

    public NotificationEventCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Set<String> supportedEventTypes() {
        return EVENT_DEFINITIONS.keySet();
    }

    public NotificationEvent decode(byte[] body) {
        if (body == null || body.length == 0) {
            throw new InvalidNotificationEventException("Notification event body is empty");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            validateEnvelopeShape(root);
            String sourceService = requiredText(root, "sourceService", 64);
            String eventType = requiredText(root, "eventType", 128);
            EventDefinition definition = EVENT_DEFINITIONS.get(eventType);
            if (definition == null) {
                throw new InvalidNotificationEventException("Unsupported notification event type");
            }
            if (!definition.sourceService().equals(sourceService)) {
                throw new InvalidNotificationEventException("Notification event source does not own this event type");
            }

            int eventVersion = requiredInteger(root, "eventVersion");
            if (eventVersion != 1
                    && !(eventVersion == 2 && Set.of("BookingRequested", "ExtensionRequested")
                    .contains(eventType))) {
                throw new InvalidNotificationEventException("Unsupported notification event version");
            }
            String aggregateType = requiredText(root, "aggregateType", 64);
            if (!definition.aggregateType().equals(aggregateType)) {
                throw new InvalidNotificationEventException("Notification event aggregate type is invalid");
            }
            UUID eventId = requiredUuid(root, "eventId");
            UUID aggregateId = requiredUuid(root, "aggregateId");
            long aggregateVersion = requiredLong(root, "aggregateVersion");
            if (aggregateVersion < 0) {
                throw new InvalidNotificationEventException("Notification event aggregate version is invalid");
            }
            String correlationId = optionalText(root, "correlationId", 128);
            String causationId = optionalText(root, "causationId", 128);
            Instant occurredAt = requiredInstant(root, "occurredAt");
            JsonNode payload = root.get("payload");
            List<UUID> recipientUserIds = validatePayload(payload, definition);
            if (eventVersion == 2) {
                validateRequestRoutingFacts(eventType, payload);
            }
            validateReminderFacts(eventType, payload);
            String resourceType = "Reminder".equals(eventType) || payload.has("reservationId")
                    ? "RESERVATION" : aggregateType;
            UUID resourceId = payload.has("reservationId")
                    ? requiredPayloadUuid(payload, "reservationId") : aggregateId;
            return new NotificationEvent(eventId, sourceService, eventType, eventVersion, aggregateType,
                    aggregateId, aggregateVersion, correlationId, causationId, occurredAt, payload,
                    recipientUserIds, resourceType, resourceId);
        } catch (JacksonException exception) {
            throw new InvalidNotificationEventException("Notification event JSON is malformed", exception);
        }
    }

    public String routingKeyFor(String eventType) {
        EventDefinition definition = EVENT_DEFINITIONS.get(eventType);
        if (definition == null) {
            throw new InvalidNotificationEventException("Unsupported notification event type");
        }
        return definition.routingKey();
    }

    private static void validateRequestRoutingFacts(String eventType, JsonNode payload) {
        String notificationRoute = requiredText(payload, "notificationRoute", 64);
        if ("BookingRequested".equals(eventType)) {
            requiredPayloadUuid(payload, "departmentId");
            JsonNode vipRoom = payload.get("vipRoom");
            if (vipRoom == null || !vipRoom.isBoolean()
                    || !Set.of("SCOPED_ADMIN_REQUEST", "NONE").contains(notificationRoute)) {
                throw new InvalidNotificationEventException("Booking request notification routing facts are invalid");
            }
            return;
        }
        if (!"SCOPED_ADMIN_REQUEST".equals(notificationRoute)) {
            throw new InvalidNotificationEventException("Extension request notification routing facts are invalid");
        }
    }

    private static void validateEnvelopeShape(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new InvalidNotificationEventException("Notification event must be a JSON object");
        }
        for (String field : root.properties().stream().map(Map.Entry::getKey).toList()) {
            if (!ENVELOPE_FIELDS.contains(field)) {
                throw new InvalidNotificationEventException("Notification event contains an unknown envelope field");
            }
        }
        for (String field : ENVELOPE_FIELDS) {
            if (!field.equals("correlationId") && !field.equals("causationId") && !root.has(field)) {
                throw new InvalidNotificationEventException("Notification event is missing a required field");
            }
        }
    }

    private static List<UUID> validatePayload(JsonNode payload, EventDefinition definition) {
        if (payload == null || !payload.isObject()) {
            throw new InvalidNotificationEventException("Notification event payload must be an object");
        }
        rejectIdentityOverrides(payload);
        JsonNode recipients = payload.get("recipientUserIds");
        if (recipients == null || !recipients.isArray()) {
            throw new InvalidNotificationEventException("Notification event recipientUserIds must be an array");
        }
        List<UUID> recipientUserIds = new ArrayList<>();
        Set<UUID> uniqueRecipients = new HashSet<>();
        for (JsonNode recipient : recipients) {
            if (!recipient.isTextual()) {
                throw new InvalidNotificationEventException("Notification event recipient identifier is invalid");
            }
            UUID recipientId = parseUuid(recipient.asText());
            if (!uniqueRecipients.add(recipientId)) {
                throw new InvalidNotificationEventException("Notification event contains duplicate recipients");
            }
            recipientUserIds.add(recipientId);
        }
        for (String fact : definition.requiredPayloadFacts()) {
            JsonNode value = payload.get(fact);
            if (value == null || value.isNull() || value.isContainer()) {
                throw new InvalidNotificationEventException("Notification event payload is missing a required fact");
            }
            if ("reminderOffsetMinutes".equals(fact)) {
                if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                    throw new InvalidNotificationEventException("Reminder offset is invalid");
                }
                continue;
            }
            if ((fact.endsWith("UserId") || fact.endsWith("Id")) && !value.isTextual()) {
                throw new InvalidNotificationEventException("Notification event identifier fact is invalid");
            }
            if (fact.endsWith("UserId") || fact.endsWith("Id")) {
                parseUuid(value.asText());
            } else if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 256) {
                throw new InvalidNotificationEventException("Notification event payload fact is invalid");
            } else if (TIMESTAMP_PAYLOAD_FACTS.contains(fact)) {
                try {
                    Instant.parse(value.asText());
                } catch (DateTimeParseException exception) {
                    throw new InvalidNotificationEventException(
                            "Notification event payload timestamp is invalid", exception);
                }
            }
        }
        return List.copyOf(recipientUserIds);
    }

    private static void validateReminderFacts(String eventType, JsonNode payload) {
        if (!"Reminder".equals(eventType)) {
            if (payload.has("reservationId") && !payload.get("reservationId").isTextual()) {
                throw new InvalidNotificationEventException("Notification event reservation identifier is invalid");
            }
            return;
        }
        int offsetMinutes = requiredInteger(payload, "reminderOffsetMinutes");
        if (offsetMinutes != 5 && offsetMinutes != 15) {
            throw new InvalidNotificationEventException("Reminder offset must be five or fifteen minutes");
        }
        Instant startsAt = requiredInstant(payload, "occurrenceStartsAt");
        Instant reminderAt = requiredInstant(payload, "reminderAt");
        String timezone = requiredText(payload, "timezone", 64);
        try {
            if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
                throw new InvalidNotificationEventException("Reminder timezone must be an IANA timezone");
            }
            ZoneId.of(timezone);
            if (!startsAt.minus(Duration.ofMinutes(offsetMinutes)).equals(reminderAt)) {
                throw new InvalidNotificationEventException("Reminder time does not match its meeting start offset");
            }
        } catch (java.time.DateTimeException exception) {
            throw new InvalidNotificationEventException("Reminder timezone or time is invalid", exception);
        }
    }

    private static void rejectIdentityOverrides(JsonNode node) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> fieldEntry : node.properties()) {
                String originalField = fieldEntry.getKey();
                String normalizedField = originalField.toLowerCase(java.util.Locale.ROOT);
                if (normalizedField.contains("email") || normalizedField.contains("displayname")) {
                    throw new InvalidNotificationEventException(
                            "Notification event must not contain contact or display-name overrides");
                }
                rejectIdentityOverrides(fieldEntry.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                rejectIdentityOverrides(child);
            }
        }
    }

    private static String optionalText(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength) {
            throw new InvalidNotificationEventException("Notification event correlation field is invalid");
        }
        return value.asText();
    }

    private static int requiredInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new InvalidNotificationEventException("Notification event version is invalid");
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new InvalidNotificationEventException("Notification event aggregate version is invalid");
        }
        return value.longValue();
    }

    private static UUID requiredUuid(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new InvalidNotificationEventException("Notification event identifier is invalid");
        }
        return parseUuid(value.asText());
    }

    private static UUID requiredPayloadUuid(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual()) {
            throw new InvalidNotificationEventException("Notification event resource identifier is invalid");
        }
        return parseUuid(value.asText());
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidNotificationEventException("Notification event identifier is invalid", exception);
        }
    }

    private static String requiredText(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > maxLength) {
            throw new InvalidNotificationEventException("Notification event text field is invalid");
        }
        return value.asText();
    }

    private static Instant requiredInstant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new InvalidNotificationEventException("Notification event timestamp is invalid");
        }
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException exception) {
            throw new InvalidNotificationEventException("Notification event timestamp is invalid", exception);
        }
    }

    private record EventDefinition(String sourceService, String routingKey, String aggregateType,
                                   Set<String> requiredPayloadFacts) {
    }
}
