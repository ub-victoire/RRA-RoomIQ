package rw.rra.roomiq.notification.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.rra.roomiq.common.web.ApiResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationTemplateActivationRequest;
import rw.rra.roomiq.notification.domain.dto.NotificationTemplateCreateRequest;
import rw.rra.roomiq.notification.domain.dto.NotificationTemplateResponse;
import rw.rra.roomiq.notification.domain.service.NotificationTemplateService;
import rw.rra.roomiq.notification.integration.NotificationIdentityAuthenticationClient;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/notification-templates")
@Tag(name = "Notification templates")
@SecurityRequirement(name = "bearerAuth")
public class NotificationTemplateController {
    private final NotificationTemplateService templateService;
    private final NotificationIdentityAuthenticationClient identityClient;

    public NotificationTemplateController(NotificationTemplateService templateService,
                                          NotificationIdentityAuthenticationClient identityClient) {
        this.templateService = templateService;
        this.identityClient = identityClient;
    }

    @GetMapping
    @Operation(summary = "List versioned notification templates",
            description = "Only an active global Super Admin with NOTIFICATION_TEMPLATE_MANAGE may administer templates.")
    public ApiResponse<List<NotificationTemplateResponse>> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        identityClient.authorizeNotificationTemplateManagement(authorization);
        return ApiResponse.success("Notification templates retrieved", templateService.list());
    }

    @PostMapping
    @Operation(summary = "Create an inactive immutable template version")
    public ApiResponse<NotificationTemplateResponse> create(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody NotificationTemplateCreateRequest request) {
        UUID actorUserId = identityClient.authorizeNotificationTemplateManagement(authorization);
        return ApiResponse.success("Notification template version created",
                templateService.create(request, actorUserId));
    }

    @PatchMapping("/{templateId}/activation")
    @Operation(summary = "Activate or deactivate a template version",
            description = "Activating one version atomically deactivates the currently active version for the same code and channel.")
    public ApiResponse<NotificationTemplateResponse> setActive(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID templateId,
            @Valid @RequestBody NotificationTemplateActivationRequest request) {
        UUID actorUserId = identityClient.authorizeNotificationTemplateManagement(authorization);
        return ApiResponse.success("Notification template activation updated",
                templateService.setActive(templateId, request, actorUserId));
    }
}
