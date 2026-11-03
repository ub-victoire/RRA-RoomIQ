package rw.rra.roomiq.notification.domain.dto;

public record NotificationPreferenceUpdateRequest(
        Boolean inAppEnabled,
        Boolean emailEnabled
) {
}
