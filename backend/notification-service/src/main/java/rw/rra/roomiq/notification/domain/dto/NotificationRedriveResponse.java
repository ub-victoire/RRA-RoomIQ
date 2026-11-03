package rw.rra.roomiq.notification.domain.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationRedriveResponse(
        UUID notificationId,
        String status,
        Instant scheduledAt,
        boolean replayed) {
}
