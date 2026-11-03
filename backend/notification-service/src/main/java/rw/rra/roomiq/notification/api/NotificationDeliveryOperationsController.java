package rw.rra.roomiq.notification.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.rra.roomiq.common.web.ApiResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationDeadLetterDetailsResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationDeadLetterPageResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationDeliveryHealthResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationRedriveResponse;
import rw.rra.roomiq.notification.domain.service.NotificationDeliveryOperationsService;
import rw.rra.roomiq.notification.integration.NotificationIdentityAuthenticationClient;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/notification-delivery")
@Tag(name = "Notification delivery operations")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class NotificationDeliveryOperationsController {
    private final NotificationDeliveryOperationsService operationsService;
    private final NotificationIdentityAuthenticationClient identityClient;

    public NotificationDeliveryOperationsController(
            NotificationDeliveryOperationsService operationsService,
            NotificationIdentityAuthenticationClient identityClient) {
        this.operationsService = operationsService;
        this.identityClient = identityClient;
    }

    @GetMapping("/health")
    @Operation(summary = "Read aggregate notification delivery health")
    public ApiResponse<NotificationDeliveryHealthResponse> health(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        identityClient.authorizeNotificationDeliveryManagement(authorization);
        return ApiResponse.success("Notification delivery health retrieved", operationsService.health());
    }

    @GetMapping("/dead-letters")
    @Operation(summary = "List dead-lettered email notifications without recipient or content data")
    public ApiResponse<NotificationDeadLetterPageResponse> listDeadLetters(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        identityClient.authorizeNotificationDeliveryManagement(authorization);
        return ApiResponse.success("Notification dead letters retrieved",
                operationsService.listDeadLetters(page, size));
    }

    @GetMapping("/dead-letters/{notificationId}")
    @Operation(summary = "Inspect sanitized attempt and redrive history")
    public ApiResponse<NotificationDeadLetterDetailsResponse> deadLetterDetails(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID notificationId) {
        identityClient.authorizeNotificationDeliveryManagement(authorization);
        return ApiResponse.success("Notification dead-letter history retrieved",
                operationsService.deadLetterDetails(notificationId));
    }

    @PostMapping("/dead-letters/{notificationId}/redrive")
    @Operation(summary = "Idempotently redrive a dead-lettered email notification")
    public ApiResponse<NotificationRedriveResponse> redrive(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID notificationId) {
        UUID actorUserId = identityClient.authorizeNotificationDeliveryManagement(authorization);
        return ApiResponse.success("Notification redrive scheduled",
                operationsService.redrive(notificationId, idempotencyKey, actorUserId));
    }
}
