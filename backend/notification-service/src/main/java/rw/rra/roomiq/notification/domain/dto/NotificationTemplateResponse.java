package rw.rra.roomiq.notification.domain.dto;

import java.util.UUID;

public record NotificationTemplateResponse(
        UUID id,
        String code,
        int version,
        String channel,
        String subject,
        String bodyTemplate,
        boolean active
) {
}
