package rw.rra.roomiq.identity.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record NotificationRecipientResolutionRequest(
        @NotBlank String contractVersion,
        @NotNull UUID eventId,
        @NotBlank String sourceService,
        @NotBlank String eventType,
        int eventVersion,
        @NotNull UUID requesterUserId,
        UUID departmentId,
        Boolean vipRoom,
        @NotBlank String notificationRoute) {
}
