package rw.rra.roomiq.notification.domain.event;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationEventCodecTests {
    private final NotificationEventCodec codec = new NotificationEventCodec(new ObjectMapper());

    @Test
    void decodesKnownVersionedEventWithZeroRecipients() {
        NotificationEvent event = codec.decode(eventJson("BookingRequested", 1, "booking-service",
                "BOOKING_REQUEST", "[]", "\"requesterUserId\":\"" + UUID.randomUUID() + "\"")
                .getBytes(StandardCharsets.UTF_8));

        assertThat(event.eventType()).isEqualTo("BookingRequested");
        assertThat(event.recipientUserIds()).isEmpty();
        assertThat(event.correlationId()).isEqualTo("correlation-1");
    }

    @Test
    void rejectsMalformedJsonAndUnsupportedVersion() {
        assertThatThrownBy(() -> codec.decode("{".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidNotificationEventException.class);

        String unsupported = eventJson("BookingRequested", 3, "booking-service",
                "BOOKING_REQUEST", "[]", "\"requesterUserId\":\"" + UUID.randomUUID() + "\"");
        assertThatThrownBy(() -> codec.decode(unsupported.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidNotificationEventException.class)
                .hasMessage("Unsupported notification event version");
    }

    @Test
    void acceptsOnlyExplicitVersionTwoRequestRoutingFacts() {
        UUID requesterId = UUID.randomUUID();
        String scopedRequest = eventJson("BookingRequested", 2, "booking-service",
                "BOOKING_REQUEST", "[]", "\"requesterUserId\":\"" + requesterId + "\","
                        + "\"departmentId\":\"" + UUID.randomUUID() + "\","
                        + "\"vipRoom\":false,\"notificationRoute\":\"SCOPED_ADMIN_REQUEST\"");
        String directRequest = eventJson("BookingRequested", 2, "booking-service",
                "BOOKING_REQUEST", "[]", "\"requesterUserId\":\"" + requesterId + "\","
                        + "\"departmentId\":\"" + UUID.randomUUID() + "\","
                        + "\"vipRoom\":false,\"notificationRoute\":\"NONE\"");
        String invalidRoute = scopedRequest.replace("SCOPED_ADMIN_REQUEST", "EVENT_RECIPIENTS");

        assertThat(codec.decode(scopedRequest.getBytes(StandardCharsets.UTF_8)).eventVersion()).isEqualTo(2);
        assertThat(codec.decode(directRequest.getBytes(StandardCharsets.UTF_8)).payload()
                .get("notificationRoute").asText()).isEqualTo("NONE");
        assertInvalid(invalidRoute);
    }

    @Test
    void acceptsVersionTwoExtensionRequestOnlyWithScopedAdminRoute() {
        String valid = eventJson("ExtensionRequested", 2, "booking-service",
                "BOOKING_EXTENSION", "[]", "\"requesterUserId\":\"" + UUID.randomUUID() + "\","
                        + "\"notificationRoute\":\"SCOPED_ADMIN_REQUEST\"");
        String invalid = valid.replace("SCOPED_ADMIN_REQUEST", "NONE");

        assertThat(codec.decode(valid.getBytes(StandardCharsets.UTF_8)).eventVersion()).isEqualTo(2);
        assertInvalid(invalid);
    }

    @Test
    void rejectsWrongSourceMissingFactsMalformedRecipientAndIdentityOverrides() {
        String wrongSource = eventJson("BookingRequested", 1, "room-service",
                "BOOKING_REQUEST", "[]", "\"requesterUserId\":\"" + UUID.randomUUID() + "\"");
        assertInvalid(wrongSource);

        String missingFact = eventJson("BookingRequested", 1, "booking-service",
                "BOOKING_REQUEST", "[]", "");
        assertInvalid(missingFact);

        String malformedRecipient = eventJson("BookingRequested", 1, "booking-service",
                "BOOKING_REQUEST", "[\"not-a-uuid\"]",
                "\"requesterUserId\":\"" + UUID.randomUUID() + "\"");
        assertInvalid(malformedRecipient);

        String identityOverride = eventJson("BookingRequested", 1, "booking-service",
                "BOOKING_REQUEST", "[]",
                "\"requesterUserId\":\"" + UUID.randomUUID() + "\",\"organizerDisplayName\":\"spoof\"");
        assertInvalid(identityOverride);
    }

    @Test
    void validatesCausationAndAggregateMetadata() {
        String wrongAggregate = eventJson("BookingRequested", 1, "booking-service",
                "RESERVATION", "[]", "\"requesterUserId\":\"" + UUID.randomUUID() + "\"");
        assertInvalid(wrongAggregate);

        String invalidCausation = eventJson("BookingRequested", 1, "booking-service",
                "BOOKING_REQUEST", "[]", "\"requesterUserId\":\"" + UUID.randomUUID() + "\"")
                .replace("\"causationId\": \"causation-1\"", "\"causationId\": \"\"");
        assertInvalid(invalidCausation);
    }

    @Test
    void validatesRoomMaintenanceEventFactsAndRoutingKey() {
        String eventJson = eventJson("RoomMaintenanceChanged", 1, "room-service", "ROOM",
                "[]", "\"roomId\":\"" + UUID.randomUUID() + "\","
                        + "\"maintenancePeriodId\":\"" + UUID.randomUUID() + "\","
                        + "\"action\":\"CREATED\",\"periodStart\":\"2026-10-13T09:00:00Z\","
                        + "\"periodEnd\":\"2026-10-13T11:00:00Z\"");

        assertThat(codec.decode(eventJson.getBytes(StandardCharsets.UTF_8)).eventType())
                .isEqualTo("RoomMaintenanceChanged");
        assertThat(codec.routingKeyFor("RoomMaintenanceChanged")).isEqualTo("room.maintenance.changed");
    }

    @Test
    void validatesExplicitMeetingReleaseFactsAndRoutingKey() {
        String validRelease = eventJson("MeetingReleased", 1, "booking-service", "MEETING", "[]",
                "\"meetingId\":\"" + UUID.randomUUID() + "\","
                        + "\"reservationId\":\"" + UUID.randomUUID() + "\","
                        + "\"releasedByUserId\":\"" + UUID.randomUUID() + "\","
                        + "\"releasedAt\":\"2026-10-12T12:00:00Z\"");

        assertThat(codec.decode(validRelease.getBytes(StandardCharsets.UTF_8)).eventType())
                .isEqualTo("MeetingReleased");
        assertThat(codec.routingKeyFor("MeetingReleased")).isEqualTo("booking.meeting.released");

        String missingActor = eventJson("MeetingReleased", 1, "booking-service", "MEETING", "[]",
                "\"meetingId\":\"" + UUID.randomUUID() + "\","
                        + "\"reservationId\":\"" + UUID.randomUUID() + "\","
                        + "\"releasedAt\":\"2026-10-12T12:00:00Z\"");
        assertInvalid(missingActor);
    }

    @Test
    void validatesReminderOffsetTimezoneAndReservationResource() {
        UUID reservationId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-10-12T12:00:00Z");
        Instant reminderAt = startsAt.minus(15, ChronoUnit.MINUTES);
        String validReminder = eventJson("Reminder", 1, "booking-service", "MEETING",
                "[\"" + UUID.randomUUID() + "\"]",
                "\"meetingId\":\"" + UUID.randomUUID() + "\","
                        + "\"reservationId\":\"" + reservationId + "\","
                        + "\"occurrenceStartsAt\":\"" + startsAt + "\","
                        + "\"timezone\":\"Africa/Kigali\","
                        + "\"reminderAt\":\"" + reminderAt + "\","
                        + "\"reminderOffsetMinutes\":15");

        NotificationEvent event = codec.decode(validReminder.getBytes(StandardCharsets.UTF_8));
        assertThat(event.eventType()).isEqualTo("Reminder");
        assertThat(event.resourceType()).isEqualTo("RESERVATION");
        assertThat(event.resourceId()).isEqualTo(reservationId);
        assertThat(codec.routingKeyFor("Reminder")).isEqualTo("notification.reminder");

        assertInvalid(validReminder.replace("Africa/Kigali", "+02:00"));
        assertInvalid(validReminder.replace("\"reminderOffsetMinutes\":15", "\"reminderOffsetMinutes\":10"));
    }

    private void assertInvalid(String json) {
        assertThatThrownBy(() -> codec.decode(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidNotificationEventException.class);
    }

    static String eventJson(String eventType, int version, String source, String aggregateType,
                            String recipientIds, String additionalPayload) {
        String comma = additionalPayload.isBlank() ? "" : ",";
        return """
                {
                  "eventId": "%s",
                  "sourceService": "%s",
                  "eventType": "%s",
                  "eventVersion": %d,
                  "aggregateType": "%s",
                  "aggregateId": "%s",
                  "aggregateVersion": 1,
                  "correlationId": "correlation-1",
                  "causationId": "causation-1",
                  "occurredAt": "2026-10-12T12:00:00Z",
                  "payload": {
                    "recipientUserIds": %s%s%s
                  }
                }
                """.formatted(UUID.randomUUID(), source, eventType, version, aggregateType,
                UUID.randomUUID(), recipientIds, comma, additionalPayload);
    }
}
