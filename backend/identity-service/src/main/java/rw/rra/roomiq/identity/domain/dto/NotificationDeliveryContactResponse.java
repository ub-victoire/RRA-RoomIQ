package rw.rra.roomiq.identity.domain.dto;

import java.util.UUID;

public record NotificationDeliveryContactResponse(UUID userId, String email) {
}
