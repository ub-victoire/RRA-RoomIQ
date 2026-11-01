package rw.rra.roomiq.notification.domain.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record NotificationInboxItemResponse(
        UUID id,
        UUID recipientUserId,
        String eventType,
        String resourceType,
        UUID resourceId,
        String status,
        Instant createdAt,
        Instant readAt,
        Instant scheduledAt,
        Instant cancelledAt,
        Map<String, Object> payload
) {
}
