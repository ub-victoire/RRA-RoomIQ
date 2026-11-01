package rw.rra.roomiq.notification.domain.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import rw.rra.roomiq.notification.domain.entity.Notification;
import rw.rra.roomiq.notification.domain.event.NotificationEvent;
import rw.rra.roomiq.notification.domain.repository.NotificationEventReceiptRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationRepository;
import rw.rra.roomiq.notification.integration.NotificationIdentityRecipientClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationEventProcessingRoutingTests {
    private final NotificationEventReceiptRepository receipts = mock(NotificationEventReceiptRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final NotificationIdentityRecipientClient identityClient =
            mock(NotificationIdentityRecipientClient.class);
    private final AtomicReference<List<Notification>> savedNotifications = new AtomicReference<>(List.of());
    private final NotificationEventProcessingService service = new NotificationEventProcessingService(
            receipts, notifications, entityManager, Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"),
            ZoneOffset.UTC), identityClient);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(receipts.insertIfAbsent(any(), any(), anyString(), anyString(), anyInt(), anyString(),
                any(), anyLong(), nullable(String.class), nullable(String.class), anyString(), any(), any()))
                .thenReturn(1);
        doAnswer(invocation -> {
            savedNotifications.set(invocation.getArgument(0));
            return null;
        }).when(notifications).saveAll(any());
    }

    @Test
    void scopedBookingRequestUsesIdentityOwnedAdminRecipientsInsteadOfEventRecipientList() {
        UUID admin = UUID.randomUUID();
        NotificationEvent event = requestEvent("BookingRequested", 2, "SCOPED_ADMIN_REQUEST",
                List.of(UUID.randomUUID()));
        when(identityClient.resolveScopedAdmins(event)).thenReturn(List.of(admin));

        assertThat(service.process(event))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED);
        assertThat(savedNotifications.get()).extracting(Notification::getRecipientUserId).containsExactly(admin);
        verify(identityClient).resolveScopedAdmins(event);
    }

    @Test
    void legacyAndDirectBookingRequestEventsDoNotGuessAdminRecipients() {
        NotificationEvent legacy = requestEvent("BookingRequested", 1, null, List.of(UUID.randomUUID()));
        NotificationEvent direct = requestEvent("BookingRequested", 2, "NONE", List.of(UUID.randomUUID()));

        assertThat(service.process(legacy))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED_WITHOUT_NOTIFICATIONS);
        assertThat(service.process(direct))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED_WITHOUT_NOTIFICATIONS);
        assertThat(savedNotifications.get()).isEmpty();
        verify(identityClient, never()).resolveScopedAdmins(any());
    }

    @Test
    void transientIdentityFailureDoesNotPersistAReceiptOrNotification() {
        NotificationEvent event = requestEvent("BookingRequested", 2, "SCOPED_ADMIN_REQUEST", List.of());
        when(identityClient.resolveScopedAdmins(event)).thenThrow(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity unavailable"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.process(event))
                .isInstanceOf(ResponseStatusException.class);

        verify(notifications, never()).saveAll(any());
        org.mockito.Mockito.verify(receipts).insertIfAbsent(any(), any(), anyString(), anyString(),
                anyInt(), anyString(), any(), anyLong(), nullable(String.class), nullable(String.class),
                anyString(), any(), any());
    }

    @Test
    void decisionCommentIsIncludedInSafeDisplayPayloadWithoutEventIdentityOverrides() throws Exception {
        UUID requester = UUID.randomUUID();
        NotificationEvent event = event("BookingRejected", 1, List.of(requester), Map.of(
                "requesterUserId", requester.toString(),
                "decidedByUserId", UUID.randomUUID().toString(),
                "decisionComment", "Please update the meeting purpose"));

        service.process(event);

        var storedPayload = objectMapper.readTree(savedNotifications.get().getFirst().getPayload());
        assertThat(storedPayload.get("decisionComment").asText())
                .isEqualTo("Please update the meeting purpose");
        assertThat(storedPayload.has("recipientUserIds")).isFalse();
    }

    @Test
    void automaticReleaseOrNoShowPersistsReceiptWithoutCreatingRecipientEffects() {
        UUID recipient = UUID.randomUUID();
        NotificationEvent event = event("AutomaticReleaseOrNoShow", 1, List.of(recipient), Map.of(
                "reservationId", UUID.randomUUID().toString(),
                "reasonCode", "NO_SHOW"));

        assertThat(service.process(event))
                .isEqualTo(NotificationEventProcessingService.ProcessingResult.PROCESSED_WITHOUT_NOTIFICATIONS);
        assertThat(savedNotifications.get()).isEmpty();
    }

    private NotificationEvent requestEvent(String eventType, int version, String route, List<UUID> recipients) {
        Map<String, Object> facts = eventType.equals("BookingRequested")
                ? Map.of("requesterUserId", UUID.randomUUID().toString(),
                "departmentId", UUID.randomUUID().toString(), "vipRoom", false)
                : Map.of("requesterUserId", UUID.randomUUID().toString());
        var payload = objectMapper.valueToTree(new java.util.LinkedHashMap<String, Object>() {{
            putAll(facts);
            put("notificationRoute", route);
        }});
        return new NotificationEvent(UUID.randomUUID(), "booking-service", eventType, version, "BOOKING_REQUEST",
                UUID.randomUUID(), 1, "correlation-1", null, Instant.parse("2026-10-12T12:00:00Z"),
                payload, recipients, "BOOKING_REQUEST", UUID.randomUUID());
    }

    private NotificationEvent event(String eventType, int version, List<UUID> recipients,
                                    Map<String, Object> facts) {
        var payload = objectMapper.valueToTree(facts);
        UUID resourceId = UUID.randomUUID();
        return new NotificationEvent(UUID.randomUUID(), "booking-service", eventType, version,
                "BOOKING_REQUEST", resourceId, 1, "correlation-1", null,
                Instant.parse("2026-10-12T12:00:00Z"), payload, recipients,
                "BOOKING_REQUEST", resourceId);
    }
}
