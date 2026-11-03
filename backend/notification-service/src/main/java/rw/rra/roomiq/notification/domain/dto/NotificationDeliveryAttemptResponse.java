package rw.rra.roomiq.notification.domain.dto;

import java.time.Instant;

public record NotificationDeliveryAttemptResponse(
        short attemptNo,
        String result,
        String providerMessageId,
        String failureReason,
        Instant attemptedAt) {
}
