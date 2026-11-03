package rw.rra.roomiq.identity.api;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.rra.roomiq.common.web.ApiResponse;
import rw.rra.roomiq.identity.domain.dto.NotificationDeliveryContactRequest;
import rw.rra.roomiq.identity.domain.dto.NotificationDeliveryContactResponse;
import rw.rra.roomiq.identity.domain.security.NotificationServiceTokenVerifier;
import rw.rra.roomiq.identity.domain.service.NotificationDeliveryContactService;

@RestController
@RequestMapping("/api/v1/internal/notifications")
@Hidden
public class NotificationDeliveryContactController {
    public static final String EMAIL_RESOLUTION_PERMISSION = "notification:delivery-contact-resolution";
    private final NotificationServiceTokenVerifier tokenVerifier;
    private final NotificationDeliveryContactService contactService;

    public NotificationDeliveryContactController(NotificationServiceTokenVerifier tokenVerifier,
                                                 NotificationDeliveryContactService contactService) {
        this.tokenVerifier = tokenVerifier;
        this.contactService = contactService;
    }

    @PostMapping("/delivery-contact")
    public ApiResponse<NotificationDeliveryContactResponse> resolve(
            @RequestHeader(name = NotificationRecipientResolutionController.SERVICE_AUTHORIZATION_HEADER,
                    required = false) String serviceAuthorization,
            @Valid @RequestBody NotificationDeliveryContactRequest request) {
        tokenVerifier.verify(serviceAuthorization, EMAIL_RESOLUTION_PERMISSION);
        return ApiResponse.success("Notification delivery contact resolved",
                contactService.resolve(request));
    }
}
