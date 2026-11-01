package rw.rra.roomiq.notification.domain.dto;

import java.util.List;

public record NotificationInboxPageResponse(
        List<NotificationInboxItemResponse> items,
        long totalElements,
        int page,
        int size,
        boolean hasNext
) {
}
