package rw.rra.roomiq.notification.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import rw.rra.roomiq.notification.domain.entity.DeliveryAttempt;
import rw.rra.roomiq.notification.domain.entity.Notification;
import rw.rra.roomiq.notification.domain.repository.DeliveryAttemptRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationDeliveryRedriveRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationRepository;
import rw.rra.roomiq.notification.integration.NotificationEmailContactClient;
import rw.rra.roomiq.notification.integration.NotificationEmailRetryProperties;
import rw.rra.roomiq.notification.integration.NotificationEmailSender;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationEmailDeliveryServiceTests {
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final DeliveryAttemptRepository deliveryAttemptRepository = mock(DeliveryAttemptRepository.class);
    private final NotificationDeliveryRedriveRepository redriveRepository =
            mock(NotificationDeliveryRedriveRepository.class);
    private final NotificationEmailContactClient contactClient = mock(NotificationEmailContactClient.class);
    private final NotificationEmailSender emailSender = mock(NotificationEmailSender.class);
    private final NotificationEmailRetryProperties retryProperties =
            new NotificationEmailRetryProperties(5, 60_000, 2, 480_000, "");
    private final Instant attemptedAt = Instant.parse("2026-03-16T12:00:00Z");
    private final Clock clock = Clock.fixed(attemptedAt, ZoneOffset.UTC);
    private NotificationEmailDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new NotificationEmailDeliveryService(notificationRepository, deliveryAttemptRepository,
                redriveRepository, contactClient, emailSender, new ObjectMapper(), retryProperties,
                new SimpleMeterRegistry(), 10, clock);
    }

    @Test
    void sendsPendingEmailAndPersistsSuccessfulAttemptWithProviderMessageId() {
        UUID userId = UUID.randomUUID();
        Notification notification = pendingEmail(userId, """
                {"subject":"Booking approved","message":"<p>Your booking is approved.</p>"}
                """);
        when(notificationRepository.lockNextDueEmail(attemptedAt))
                .thenReturn(Optional.of(notification), Optional.empty());
        when(deliveryAttemptRepository.findMaximumAttemptNumberSince(
                notification.getId(), notification.getCreatedAt())).thenReturn((short) 2);
        when(contactClient.resolveActiveAddress(userId)).thenReturn("trusted@example.org");
        when(emailSender.send(notification.getId(), "trusted@example.org", "Booking approved",
                "<p>Your booking is approved.</p>")).thenReturn("relay-message-42");

        assertThat(service.deliverPendingEmails()).isEqualTo(1);

        assertThat(notification.getStatus()).isEqualTo("SENT");
        var attempt = captureAttempt();
        assertThat(attempt.getAttemptNo()).isEqualTo((short) 3);
        assertThat(attempt.getResult()).isEqualTo("SENT");
        assertThat(attempt.getProviderMessageId()).isEqualTo("relay-message-42");
        assertThat(attempt.getFailureReason()).isNull();
        assertThat(attempt.getAttemptedAt()).isEqualTo(attemptedAt);
    }

    @Test
    void recordsSanitizedFailureAndDoesNotPersistProviderExceptionDetails() {
        UUID userId = UUID.randomUUID();
        Notification notification = pendingEmail(userId, """
                {"subject":"Booking approved","message":"<p>Your booking is approved.</p>"}
                """);
        when(notificationRepository.lockNextDueEmail(attemptedAt))
                .thenReturn(Optional.of(notification), Optional.empty());
        when(deliveryAttemptRepository.findMaximumAttemptNumberSince(
                notification.getId(), notification.getCreatedAt())).thenReturn(null);
        when(contactClient.resolveActiveAddress(userId)).thenReturn("trusted@example.org");
        when(emailSender.send(any(), any(), any(), any()))
                .thenThrow(new MailSendException("provider secret and recipient details"));

        assertThat(service.deliverPendingEmails()).isZero();

        assertThat(notification.getStatus()).isEqualTo("SCHEDULED");
        assertThat(notification.getScheduledAt()).isEqualTo(attemptedAt.plusSeconds(60));
        var attempt = captureAttempt();
        assertThat(attempt.getAttemptNo()).isEqualTo((short) 1);
        assertThat(attempt.getResult()).isEqualTo("FAILED");
        assertThat(attempt.getFailureReason()).isEqualTo("SMTP_TEMPORARY_PROVIDER_FAILURE");
        assertThat(attempt.getFailureReason()).doesNotContain("secret", "recipient");
        assertThat(attempt.getProviderMessageId()).isNull();
    }

    @Test
    void invalidStoredEmailContentIsRecordedWithoutContactLookupOrSend() {
        Notification notification = pendingEmail(UUID.randomUUID(), "{not-json");
        when(notificationRepository.lockNextDueEmail(attemptedAt))
                .thenReturn(Optional.of(notification), Optional.empty());
        when(deliveryAttemptRepository.findMaximumAttemptNumberSince(
                notification.getId(), notification.getCreatedAt())).thenReturn(null);

        assertThat(service.deliverPendingEmails()).isZero();

        assertThat(notification.getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(captureAttempt().getFailureReason()).isEqualTo("EMAIL_CONTENT_INVALID");
        verifyNoInteractions(contactClient, emailSender);
    }

    @Test
    void permanentSmtpAuthenticationFailureIsDeadLetteredWithoutRetry() {
        Notification notification = pendingEmail(UUID.randomUUID(), """
                {"subject":"Booking approved","message":"<p>Your booking is approved.</p>"}
                """);
        when(notificationRepository.lockNextDueEmail(attemptedAt))
                .thenReturn(Optional.of(notification), Optional.empty());
        when(deliveryAttemptRepository.findMaximumAttemptNumberSince(
                notification.getId(), notification.getCreatedAt())).thenReturn(null);
        when(contactClient.resolveActiveAddress(notification.getRecipientUserId()))
                .thenReturn("trusted@example.org");
        when(emailSender.send(any(), any(), any(), any()))
                .thenThrow(new MailAuthenticationException("secret credential detail"));

        assertThat(service.deliverPendingEmails()).isZero();

        assertThat(notification.getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(captureAttempt().getFailureReason()).isEqualTo("SMTP_CONFIGURATION_INVALID");
    }

    @Test
    void exhaustedTransientAttemptsAreDeadLetteredAndPreserveFailureHistory() {
        Notification notification = pendingEmail(UUID.randomUUID(), """
                {"subject":"Booking approved","message":"<p>Your booking is approved.</p>"}
                """);
        when(notificationRepository.lockNextDueEmail(attemptedAt))
                .thenReturn(Optional.of(notification), Optional.empty());
        when(deliveryAttemptRepository.findMaximumAttemptNumberSince(
                notification.getId(), notification.getCreatedAt())).thenReturn((short) 4);
        when(contactClient.resolveActiveAddress(notification.getRecipientUserId()))
                .thenReturn("trusted@example.org");
        when(emailSender.send(any(), any(), any(), any()))
                .thenThrow(new MailSendException("temporary provider failure"));

        assertThat(service.deliverPendingEmails()).isZero();

        assertThat(notification.getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(captureAttempt().getAttemptNo()).isEqualTo((short) 5);
        verify(deliveryAttemptRepository).save(any(DeliveryAttempt.class));
    }

    private DeliveryAttempt captureAttempt() {
        var captor = org.mockito.ArgumentCaptor.forClass(DeliveryAttempt.class);
        verify(deliveryAttemptRepository).save(captor.capture());
        return captor.getValue();
    }

    private static Notification pendingEmail(UUID userId, String payload) {
        return new Notification(UUID.randomUUID(), userId, "BookingApproved", "RESERVATION", UUID.randomUUID(),
                "EMAIL", payload, Instant.parse("2026-03-16T11:00:00Z"), "PENDING");
    }
}
