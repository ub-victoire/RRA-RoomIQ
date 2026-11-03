package rw.rra.roomiq.notification.domain.dto;

import jakarta.validation.constraints.NotNull;

public record NotificationTemplateActivationRequest(@NotNull Boolean active) {
}
