package rw.rra.roomiq.notification.domain.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.notification.domain.dto.NotificationDeadLetterDetailsResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationDeadLetterItemResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationDeadLetterPageResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationDeliveryAttemptResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationDeliveryHealthResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationDeliveryRedriveHistoryResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationRedriveResponse;
import rw.rra.roomiq.notification.domain.entity.DeliveryAttempt;
import rw.rra.roomiq.notification.domain.entity.NotificationDeliveryRedrive;
import rw.rra.roomiq.notification.domain.repository.DeliveryAttemptRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationDeliveryRedriveRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Service
public class NotificationDeliveryOperationsService {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final NotificationRepository notificationRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final NotificationDeliveryRedriveRepository redriveRepository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public NotificationDeliveryOperationsService(
            NotificationRepository notificationRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            NotificationDeliveryRedriveRepository redriveRepository,
            MeterRegistry meterRegistry) {
        this(notificationRepository, deliveryAttemptRepository, redriveRepository,
                meterRegistry, Clock.systemUTC());
    }

    NotificationDeliveryOperationsService(
            NotificationRepository notificationRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            NotificationDeliveryRedriveRepository redriveRepository,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.notificationRepository = notificationRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.redriveRepository = redriveRepository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotificationDeliveryHealthResponse health() {
        Timer timer = meterRegistry.find("roomiq.notification.email.delivery.latency").timer();
        long sampleCount = timer == null ? 0 : timer.count();
        double averageMs = sampleCount == 0 ? 0 : timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS);
        double maximumMs = sampleCount == 0 ? 0 : timer.max(java.util.concurrent.TimeUnit.MILLISECONDS);
        return new NotificationDeliveryHealthResponse(
                notificationRepository.countQueuedEmails(),
                notificationRepository.findOldestQueuedEmailAt(),
                deliveryAttemptRepository.countRetryAttempts(),
                notificationRepository.countDeadLetteredEmails(),
                deliveryAttemptRepository.countSuccessfulAttempts(),
                deliveryAttemptRepository.countFailedAttempts(),
                sampleCount,
                averageMs,
                maximumMs);
    }

    @Transactional(readOnly = true)
    public NotificationDeadLetterPageResponse listDeadLetters(int page, int size) {
        validatePage(page, size);
        Page<rw.rra.roomiq.notification.domain.entity.Notification> result =
                notificationRepository.findByChannelAndStatusOrderByCreatedAtAsc(
                        "EMAIL", "DEAD_LETTER",
                        PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt")));
        List<UUID> notificationIds = result.getContent().stream()
                .map(rw.rra.roomiq.notification.domain.entity.Notification::getId).toList();
        Map<UUID, List<DeliveryAttempt>> attemptsByNotification = notificationIds.isEmpty()
                ? Map.of()
                : deliveryAttemptRepository.findAllByNotificationIds(notificationIds).stream()
                        .collect(Collectors.groupingBy(attempt -> attempt.getNotification().getId()));
        List<NotificationDeadLetterItemResponse> items = result.getContent().stream()
                .map(notification -> {
                    List<DeliveryAttempt> attempts = attemptsByNotification.getOrDefault(
                            notification.getId(), List.of());
                    var lastAttempt = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
                    return new NotificationDeadLetterItemResponse(
                            notification.getId(), notification.getEventType(), notification.getCreatedAt(),
                            attempts.size(), lastAttempt == null ? null : lastAttempt.getFailureReason());
                })
                .toList();
        return new NotificationDeadLetterPageResponse(
                items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public NotificationDeadLetterDetailsResponse deadLetterDetails(UUID notificationId) {
        var notification = notificationRepository.findByIdAndChannelAndStatus(
                        notificationId, "EMAIL", "DEAD_LETTER")
                .orElseThrow(NotificationDeliveryOperationsService::deadLetterNotFound);
        List<NotificationDeliveryAttemptResponse> attempts = deliveryAttemptRepository
                .findAllByNotificationIdOrderByAttemptedAtAsc(notificationId).stream()
                .map(attempt -> new NotificationDeliveryAttemptResponse(attempt.getAttemptNo(), attempt.getResult(),
                        attempt.getProviderMessageId(), attempt.getFailureReason(), attempt.getAttemptedAt()))
                .toList();
        List<NotificationDeliveryRedriveHistoryResponse> redrives = redriveRepository
                .findAllByNotificationIdOrderByRequestedAtAsc(notificationId).stream()
                .map(redrive -> new NotificationDeliveryRedriveHistoryResponse(redrive.getRequestedAt()))
                .toList();
        return new NotificationDeadLetterDetailsResponse(
                notification.getId(), notification.getEventType(), notification.getCreatedAt(), attempts, redrives);
    }

    @Transactional
    public NotificationRedriveResponse redrive(UUID notificationId, String idempotencyKey, UUID actorUserId) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches() || actorUserId == null) {
            throw invalidRedriveRequest();
        }
        var notification = notificationRepository.lockEmailById(notificationId)
                .orElseThrow(NotificationDeliveryOperationsService::deadLetterNotFound);
        var previous = redriveRepository.findByNotificationIdAndIdempotencyKey(notificationId, idempotencyKey);
        if (previous.isPresent()) {
            return response(notification, true);
        }
        if (!"DEAD_LETTER".equals(notification.getStatus())) {
            throw new DomainException(HttpStatus.CONFLICT, "NOTIFICATION_NOT_DEAD_LETTERED",
                    "Only dead-lettered email notifications can be redriven");
        }

        Instant requestedAt = clock.instant();
        List<DeliveryAttempt> latestAttempts = deliveryAttemptRepository.findLatestAttempt(
                notificationId, PageRequest.of(0, 1));
        if (!latestAttempts.isEmpty() && !requestedAt.isAfter(latestAttempts.getFirst().getAttemptedAt())) {
            requestedAt = latestAttempts.getFirst().getAttemptedAt().plus(1, ChronoUnit.MICROS);
        }
        redriveRepository.save(new NotificationDeliveryRedrive(
                UUID.randomUUID(), notification, idempotencyKey, actorUserId, requestedAt));
        notification.redriveEmail(requestedAt);
        notificationRepository.save(notification);
        return response(notification, false);
    }

    private static NotificationRedriveResponse response(
            rw.rra.roomiq.notification.domain.entity.Notification notification, boolean replayed) {
        return new NotificationRedriveResponse(notification.getId(), notification.getStatus(),
                notification.getScheduledAt(), replayed);
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_DELIVERY_PAGE",
                    "Page must be non-negative and size must be between 1 and 100");
        }
    }

    private static DomainException invalidRedriveRequest() {
        return new DomainException(HttpStatus.BAD_REQUEST, "INVALID_DELIVERY_REDRIVE",
                "A valid idempotency key and authorized actor are required");
    }

    private static DomainException deadLetterNotFound() {
        return new DomainException(HttpStatus.NOT_FOUND, "NOTIFICATION_DEAD_LETTER_NOT_FOUND",
                "Dead-lettered email notification was not found");
    }
}
