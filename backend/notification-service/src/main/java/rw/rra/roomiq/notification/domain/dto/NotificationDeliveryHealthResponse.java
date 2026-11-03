package rw.rra.roomiq.notification.domain.dto;

import java.time.Instant;

public record NotificationDeliveryHealthResponse(
        long emailQueueDepth,
        Instant oldestQueuedEmailAt,
        long retryAttempts,
        long deadLetterCount,
        long successfulAttempts,
        long failedAttempts,
        long latencySampleCount,
        double averageLatencyMs,
        double maximumLatencyMs) {
}
