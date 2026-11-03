package rw.rra.roomiq.notification.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.rra.roomiq.common.web.ApiResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationInboxItemResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationInboxPageResponse;
import rw.rra.roomiq.notification.domain.service.NotificationInboxService;

import java.util.UUID;

@RestController
@RequestMapping({"/api/v1", "/api/v1/me"})
@Tag(name = "Notifications")
@SecurityRequirement(name = "bearerAuth")
public class NotificationInboxController {
    private final NotificationInboxService notificationInboxService;

    public NotificationInboxController(NotificationInboxService notificationInboxService) {
        this.notificationInboxService = notificationInboxService;
    }

    @GetMapping({"/notifications", "/inbox"})
    @Operation(summary = "List this user’s inbox notifications",
            description = "Returns the authenticated identity’s notifications with optional status/event/read filters and pagination.")
    public ApiResponse<NotificationInboxPageResponse> listInbox(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean read,
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer size,
            @RequestParam(required = false, defaultValue = "createdAt") String sortBy,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection) {
        return ApiResponse.success("Notifications retrieved",
                notificationInboxService.list(authorization, eventType, status, read, page, size, sortBy,
                        sortDirection));
    }

    @PatchMapping({"/notifications/{notificationId}/read", "/inbox/{notificationId}/read"})
    @Operation(summary = "Mark one notification as read",
            description = "Only the owning authenticated user may read their own notification record.")
    public ApiResponse<NotificationInboxItemResponse> markRead(
                                                              @RequestHeader(value = HttpHeaders.AUTHORIZATION,
                                                                      required = false) String authorization,
                                                              @PathVariable UUID notificationId) {
        return ApiResponse.success("Notification marked as read",
                notificationInboxService.markRead(authorization, notificationId));
    }
}
