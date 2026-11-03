package rw.rra.roomiq.notification.domain.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NotificationDeadLetterDetailsResponse(
        UUID notificationId,
        String eventType,
        Instant createdAt,
        List<NotificationDeliveryAttemptResponse> attempts,
        List<NotificationDeliveryRedriveHistoryResponse> redrives) {
}
