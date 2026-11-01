package rw.rra.roomiq.identity.domain.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import rw.rra.roomiq.identity.domain.dto.NotificationRecipientResolutionRequest;
import rw.rra.roomiq.identity.domain.dto.NotificationRecipientResolutionResponse;
import rw.rra.roomiq.identity.domain.repository.UserRoleRepository;
import rw.rra.roomiq.identity.domain.security.IdentityAuthorizationService;

import java.util.List;
import java.util.UUID;

@Service
public class NotificationRecipientResolutionService {
    private static final String CONTRACT_VERSION = "v1";
    private static final String BOOKING_SOURCE = "booking-service";
    private static final String SCOPED_ADMIN_ROUTE = "SCOPED_ADMIN_REQUEST";

    private final IdentityAuthorizationService authorization;
    private final UserRoleRepository userRoles;

    public NotificationRecipientResolutionService(IdentityAuthorizationService authorization,
                                                   UserRoleRepository userRoles) {
        this.authorization = authorization;
        this.userRoles = userRoles;
    }

    @Transactional(readOnly = true)
    public NotificationRecipientResolutionResponse resolve(NotificationRecipientResolutionRequest request) {
        validateContract(request);
        UUID buildingId;
        if ("BookingRequested".equals(request.eventType())) {
            if (request.departmentId() == null || request.vipRoom() == null) {
                throw invalidContext();
            }
            buildingId = authorization.authorizedNotificationBookingRequestBuilding(
                    request.requesterUserId(), request.departmentId(), request.vipRoom());
        } else {
            if (request.departmentId() != null || request.vipRoom() != null) {
                throw invalidContext();
            }
            buildingId = authorization.authorizedNotificationExtensionRequestBuilding(
                    request.requesterUserId());
        }
        if (buildingId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Requester is not currently authorized for this notification context");
        }
        List<UUID> recipients = userRoles.findActiveAdminUserIdsByBuildingId(buildingId);
        return new NotificationRecipientResolutionResponse(
                CONTRACT_VERSION, request.eventId(), request.eventType(), buildingId, recipients);
    }

    private static void validateContract(NotificationRecipientResolutionRequest request) {
        if (!CONTRACT_VERSION.equals(request.contractVersion())
                || !BOOKING_SOURCE.equals(request.sourceService())
                || request.eventVersion() != 2
                || !SCOPED_ADMIN_ROUTE.equals(request.notificationRoute())
                || !("BookingRequested".equals(request.eventType())
                || "ExtensionRequested".equals(request.eventType()))) {
            throw invalidContext();
        }
    }

    private static ResponseStatusException invalidContext() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Notification recipient request context is invalid");
    }
}
