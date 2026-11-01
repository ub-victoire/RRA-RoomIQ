package rw.rra.roomiq.identity.api;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.rra.roomiq.common.web.ApiResponse;
import rw.rra.roomiq.identity.domain.dto.NotificationRecipientResolutionRequest;
import rw.rra.roomiq.identity.domain.dto.NotificationRecipientResolutionResponse;
import rw.rra.roomiq.identity.domain.security.NotificationServiceTokenVerifier;
import rw.rra.roomiq.identity.domain.service.NotificationRecipientResolutionService;

@RestController
@RequestMapping("/api/v1/internal/notifications")
@Hidden
public class NotificationRecipientResolutionController {
    public static final String SERVICE_AUTHORIZATION_HEADER = "X-RoomIQ-Service-Authorization";

    private final NotificationServiceTokenVerifier tokenVerifier;
    private final NotificationRecipientResolutionService resolutionService;

    public NotificationRecipientResolutionController(NotificationServiceTokenVerifier tokenVerifier,
                                                       NotificationRecipientResolutionService resolutionService) {
        this.tokenVerifier = tokenVerifier;
        this.resolutionService = resolutionService;
    }

    @PostMapping("/recipient-resolution")
    public ApiResponse<NotificationRecipientResolutionResponse> resolve(
            @RequestHeader(name = SERVICE_AUTHORIZATION_HEADER, required = false) String serviceAuthorization,
            @Valid @RequestBody NotificationRecipientResolutionRequest request) {
        tokenVerifier.verify(serviceAuthorization);
        return ApiResponse.success("Notification recipients resolved", resolutionService.resolve(request));
    }
}
