package rw.rra.roomiq.notification.domain.dto;

import java.util.List;

public record NotificationDeadLetterPageResponse(
        List<NotificationDeadLetterItemResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
