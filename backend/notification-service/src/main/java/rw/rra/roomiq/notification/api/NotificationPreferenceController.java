package rw.rra.roomiq.notification.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.rra.roomiq.common.web.ApiResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationPreferenceResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationPreferenceUpdateRequest;
import rw.rra.roomiq.notification.domain.service.NotificationPreferenceService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/me/notification-preferences")
@Tag(name = "Notifications")
@SecurityRequirement(name = "bearerAuth")
public class NotificationPreferenceController {
    private final NotificationPreferenceService preferenceService;

    public NotificationPreferenceController(NotificationPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @GetMapping
    @Operation(summary = "List this user's notification channel preferences",
            description = "Returns effective per-event settings for the authenticated Identity account.")
    public ApiResponse<List<NotificationPreferenceResponse>> getPreferences(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ApiResponse.success("Notification preferences retrieved",
                preferenceService.getPreferences(authorization));
    }

    @PutMapping("/{eventType}")
    @Operation(summary = "Replace this user's channel preferences for one event type",
            description = "Identity derives the preference owner; repeating the same update is safe.")
    public ApiResponse<NotificationPreferenceResponse> updatePreference(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String eventType,
            @RequestBody(required = false) NotificationPreferenceUpdateRequest request) {
        return ApiResponse.success("Notification preference updated",
                preferenceService.updatePreference(authorization, eventType, request));
    }
}
