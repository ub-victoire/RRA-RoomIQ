package rw.rra.roomiq.identity.domain.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record NotificationDeliveryContactRequest(@NotNull UUID userId) {
}
