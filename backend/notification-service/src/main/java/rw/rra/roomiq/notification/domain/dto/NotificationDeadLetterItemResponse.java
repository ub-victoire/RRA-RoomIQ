package rw.rra.roomiq.notification.domain.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationDeadLetterItemResponse(
        UUID notificationId,
        String eventType,
        Instant createdAt,
        int attemptCount,
        String lastFailureReason) {
}
