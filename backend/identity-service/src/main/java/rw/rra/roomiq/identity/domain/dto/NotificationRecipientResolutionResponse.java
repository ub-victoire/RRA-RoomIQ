package rw.rra.roomiq.identity.domain.dto;

import java.util.List;
import java.util.UUID;

public record NotificationRecipientResolutionResponse(
        String contractVersion,
        UUID eventId,
        String eventType,
        UUID officeBuildingId,
        List<UUID> recipientUserIds) {
}
