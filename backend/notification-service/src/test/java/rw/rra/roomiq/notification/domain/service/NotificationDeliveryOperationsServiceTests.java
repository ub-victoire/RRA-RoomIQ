package rw.rra.roomiq.notification.domain.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.notification.domain.entity.Notification;
import rw.rra.roomiq.notification.domain.entity.NotificationDeliveryRedrive;
import rw.rra.roomiq.notification.domain.repository.DeliveryAttemptRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationDeliveryRedriveRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NotificationDeliveryOperationsServiceTests {
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final DeliveryAttemptRepository deliveryAttemptRepository = mock(DeliveryAttemptRepository.class);
    private final NotificationDeliveryRedriveRepository redriveRepository =
            mock(NotificationDeliveryRedriveRepository.class);
    private final Instant now = Instant.parse("2026-03-16T12:00:00Z");
    private final NotificationDeliveryOperationsService service = new NotificationDeliveryOperationsService(
            notificationRepository, deliveryAttemptRepository, redriveRepository,
            new SimpleMeterRegistry(), Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void redriveSchedulesExistingNotificationAndRepeatedKeyDoesNotCreateAnotherRedrive() {
        UUID actorId = UUID.randomUUID();
        Notification notification = deadLetter();
        when(notificationRepository.lockEmailById(notification.getId())).thenReturn(Optional.of(notification));
        when(redriveRepository.findByNotificationIdAndIdempotencyKey(notification.getId(), "redrive-42"))
                .thenReturn(Optional.empty(), Optional.of(new NotificationDeliveryRedrive(
                        UUID.randomUUID(), notification, "redrive-42", actorId, now)));

        var first = service.redrive(notification.getId(), "redrive-42", actorId);
        var replay = service.redrive(notification.getId(), "redrive-42", actorId);

        assertThat(first.status()).isEqualTo("SCHEDULED");
        assertThat(first.scheduledAt()).isEqualTo(now);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.status()).isEqualTo("SCHEDULED");
        assertThat(replay.replayed()).isTrue();
        verify(redriveRepository, times(1)).save(any(NotificationDeliveryRedrive.class));
        verify(notificationRepository, times(1)).save(notification);
    }

    @Test
    void rejectsNewRedriveKeysForNotificationsThatAreNotDeadLettered() {
        Notification notification = new Notification(UUID.randomUUID(), UUID.randomUUID(),
                "BookingApproved", "RESERVATION", UUID.randomUUID(), "EMAIL", "{}",
                now, "SENT");
        when(notificationRepository.lockEmailById(notification.getId())).thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> service.redrive(notification.getId(), "new-key", UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
        verify(redriveRepository).findByNotificationIdAndIdempotencyKey(notification.getId(), "new-key");
        verify(redriveRepository, never()).save(any(NotificationDeliveryRedrive.class));
    }

    @Test
    void rejectsMalformedIdempotencyKeysBeforeDatabaseMutation() {
        assertThatThrownBy(() -> service.redrive(UUID.randomUUID(), "key contains spaces", UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
        verifyNoInteractions(notificationRepository, redriveRepository);
    }

    @Test
    void deadLetterListOmitsRecipientAndStoredMessageContent() {
        Notification notification = new Notification(UUID.randomUUID(), UUID.randomUUID(),
                "BookingApproved", "RESERVATION", UUID.randomUUID(), "EMAIL",
                "{\"message\":\"private body\"}", now, "DEAD_LETTER");
        when(notificationRepository.findByChannelAndStatusOrderByCreatedAtAsc(
                eq("EMAIL"), eq("DEAD_LETTER"), any()))
                .thenReturn(new PageImpl<>(List.of(notification)));
        when(deliveryAttemptRepository.findAllByNotificationIdOrderByAttemptedAtAsc(notification.getId()))
                .thenReturn(List.of());

        var page = service.listDeadLetters(0, 25);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().eventType()).isEqualTo("BookingApproved");
        assertThat(page.items().getFirst().toString()).doesNotContain("private body");
        assertThat(page.items().getFirst().toString()).doesNotContain(notification.getRecipientUserId().toString());
    }

    private Notification deadLetter() {
        return new Notification(UUID.randomUUID(), UUID.randomUUID(),
                "BookingApproved", "RESERVATION", UUID.randomUUID(), "EMAIL", "{}",
                now.minusSeconds(30), "DEAD_LETTER");
    }
}
