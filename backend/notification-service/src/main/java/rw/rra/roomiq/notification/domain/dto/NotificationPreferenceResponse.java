package rw.rra.roomiq.notification.domain.dto;

public record NotificationPreferenceResponse(
        String eventType,
        boolean inAppEnabled,
        boolean emailEnabled,
        boolean configured
) {
}
