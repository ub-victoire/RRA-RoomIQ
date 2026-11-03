package rw.rra.roomiq.notification.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import rw.rra.roomiq.notification.domain.entity.DeliveryAttempt;
import rw.rra.roomiq.notification.domain.entity.Notification;
import rw.rra.roomiq.notification.domain.repository.DeliveryAttemptRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationDeliveryRedriveRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationRepository;
import rw.rra.roomiq.notification.integration.NotificationEmailContactClient;
import rw.rra.roomiq.notification.integration.NotificationEmailFailureClassifier;
import rw.rra.roomiq.notification.integration.NotificationEmailRetryProperties;
import rw.rra.roomiq.notification.integration.NotificationEmailSender;
import rw.rra.roomiq.notification.integration.SmtpNotificationEmailSender;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "roomiq.notification.email.enabled", havingValue = "true")
public class NotificationEmailDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(NotificationEmailDeliveryService.class);

    private final NotificationRepository notificationRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final NotificationDeliveryRedriveRepository redriveRepository;
    private final NotificationEmailContactClient contactClient;
    private final NotificationEmailSender emailSender;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int batchSize;
    private final NotificationEmailRetryProperties retryProperties;
    private final NotificationEmailFailureClassifier failureClassifier;
    private final MeterRegistry meterRegistry;

    @Autowired
    public NotificationEmailDeliveryService(
            NotificationRepository notificationRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            NotificationDeliveryRedriveRepository redriveRepository,
            NotificationEmailContactClient contactClient,
            NotificationEmailSender emailSender,
            ObjectMapper objectMapper,
            NotificationEmailRetryProperties retryProperties,
            MeterRegistry meterRegistry,
            @org.springframework.beans.factory.annotation.Value("${roomiq.notification.email.batch-size:25}")
            int batchSize) {
        this(notificationRepository, deliveryAttemptRepository, redriveRepository, contactClient, emailSender,
                objectMapper, retryProperties, meterRegistry, batchSize, Clock.systemUTC());
    }

    NotificationEmailDeliveryService(
            NotificationRepository notificationRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            NotificationDeliveryRedriveRepository redriveRepository,
            NotificationEmailContactClient contactClient,
            NotificationEmailSender emailSender,
            ObjectMapper objectMapper,
            NotificationEmailRetryProperties retryProperties,
            MeterRegistry meterRegistry,
            int batchSize,
            Clock clock) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Notification email batch size must be between 1 and 500");
        }
        this.notificationRepository = notificationRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.redriveRepository = redriveRepository;
        this.contactClient = contactClient;
        this.emailSender = emailSender;
        this.objectMapper = objectMapper;
        this.retryProperties = retryProperties;
        this.failureClassifier = new NotificationEmailFailureClassifier(retryProperties);
        this.meterRegistry = meterRegistry;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    @Transactional
    public int deliverPendingEmails() {
        int delivered = 0;
        for (int i = 0; i < batchSize; i++) {
            var candidate = notificationRepository.lockNextDueEmail(clock.instant());
            if (candidate.isEmpty()) {
                break;
            }
            if ("SCHEDULED".equals(candidate.get().getStatus())) {
                candidate.get().prepareScheduledEmailRetry();
            }
            if (deliver(candidate.get())) {
                delivered++;
            }
        }
        return delivered;
    }

    private boolean deliver(Notification notification) {
        Instant attemptedAt = clock.instant();
        Instant cycleStartedAt = redriveRepository.findFirstByNotificationIdOrderByRequestedAtDesc(
                notification.getId()).map(redrive -> redrive.getRequestedAt()).orElse(notification.getCreatedAt());
        short attemptNo = nextAttemptNumber(notification.getId(), cycleStartedAt);
        String result = "SENT";
        String providerMessageId = null;
        String failureReason = null;
        boolean retryable = false;
        Timer.Sample timer = Timer.start(meterRegistry);
        try {
            EmailContent content = readContent(notification.getPayload());
            String recipientAddress = contactClient.resolveActiveAddress(notification.getRecipientUserId());
            providerMessageId = emailSender.send(notification.getId(), recipientAddress,
                    content.subject(), content.body());
            notification.markEmailSent();
        } catch (NotificationEmailContactClient.NotificationEmailContactException exception) {
            failureReason = exception.reasonCode();
            retryable = exception.retryable();
        } catch (SmtpNotificationEmailSender.NotificationEmailFormattingException exception) {
            failureReason = "EMAIL_MESSAGE_FORMATTING_FAILED";
        } catch (MailException exception) {
            NotificationEmailFailureClassifier.Classification classification =
                    failureClassifier.classify(exception);
            failureReason = classification.reasonCode();
            retryable = classification.retryable();
        } catch (InvalidEmailContentException exception) {
            failureReason = "EMAIL_CONTENT_INVALID";
        }

        if (failureReason != null) {
            result = "FAILED";
            if (retryable && retryProperties.hasRetryRemaining(attemptNo)) {
                notification.scheduleEmailRetry(clock.instant().plusMillis(
                        retryProperties.delayAfterAttemptMs(attemptNo)));
            } else {
                notification.markEmailDeadLettered();
            }
        }
        notificationRepository.save(notification);
        deliveryAttemptRepository.save(new DeliveryAttempt(UUID.randomUUID(), notification, attemptNo,
                result, providerMessageId, failureReason, attemptedAt));
        timer.stop(Timer.builder("roomiq.notification.email.delivery.latency")
                .tag("outcome", result).register(meterRegistry));
        if ("FAILED".equals(result)) {
            log.warn("Notification email delivery failed attemptNo={} outcome={} reason={}",
                    attemptNo, notification.getStatus(), failureReason);
        }
        return "SENT".equals(result);
    }

    private short nextAttemptNumber(UUID notificationId, Instant cycleStartedAt) {
        Short previous = deliveryAttemptRepository.findMaximumAttemptNumberSince(notificationId, cycleStartedAt);
        int next = previous == null ? 1 : previous + 1;
        if (next > Short.MAX_VALUE) {
            throw new IllegalStateException("Delivery attempt number exceeded the approved schema range");
        }
        return (short) next;
    }

    private EmailContent readContent(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode subject = root == null ? null : root.get("subject");
            JsonNode body = root == null ? null : root.get("message");
            if (subject == null || !subject.isTextual() || subject.asText().isBlank()
                    || subject.asText().contains("\r") || subject.asText().contains("\n")
                    || body == null || !body.isTextual() || body.asText().isBlank()) {
                throw new InvalidEmailContentException();
            }
            return new EmailContent(subject.asText(), body.asText());
        } catch (JacksonException exception) {
            throw new InvalidEmailContentException();
        }
    }

    private record EmailContent(String subject, String body) {
    }

    private static final class InvalidEmailContentException extends RuntimeException {
    }
}
