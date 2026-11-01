package rw.rra.roomiq.identity.domain.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import rw.rra.roomiq.identity.domain.dto.NotificationRecipientResolutionRequest;
import rw.rra.roomiq.identity.domain.repository.UserRoleRepository;
import rw.rra.roomiq.identity.domain.security.IdentityAuthorizationService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationRecipientResolutionServiceTests {
    private final IdentityAuthorizationService authorization = mock(IdentityAuthorizationService.class);
    private final UserRoleRepository userRoles = mock(UserRoleRepository.class);
    private final NotificationRecipientResolutionService service =
            new NotificationRecipientResolutionService(authorization, userRoles);

    @Test
    void resolvesOnlyIdentityOwnedAdminsForTheAuthorizedRequesterBuilding() {
        UUID requesterId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        List<UUID> admins = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(authorization.authorizedNotificationBookingRequestBuilding(requesterId, departmentId, false))
                .thenReturn(buildingId);
        when(userRoles.findActiveAdminUserIdsByBuildingId(buildingId)).thenReturn(admins);

        var response = service.resolve(request(eventId, "BookingRequested", requesterId, departmentId, false));

        assertThat(response.contractVersion()).isEqualTo("v1");
        assertThat(response.eventId()).isEqualTo(eventId);
        assertThat(response.officeBuildingId()).isEqualTo(buildingId);
        assertThat(response.recipientUserIds()).containsExactlyElementsOf(admins);
        verify(userRoles).findActiveAdminUserIdsByBuildingId(buildingId);
    }

    @Test
    void extensionRoutingUsesCurrentRequesterBuildingAndDoesNotAcceptCallerScope() {
        UUID requesterId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();
        when(authorization.authorizedNotificationExtensionRequestBuilding(requesterId)).thenReturn(buildingId);
        when(userRoles.findActiveAdminUserIdsByBuildingId(buildingId)).thenReturn(List.of());

        var response = service.resolve(request(UUID.randomUUID(), "ExtensionRequested", requesterId, null, null));

        assertThat(response.officeBuildingId()).isEqualTo(buildingId);
        assertThat(response.recipientUserIds()).isEmpty();
        verify(authorization).authorizedNotificationExtensionRequestBuilding(requesterId);
    }

    @Test
    void rejectsUnsupportedVersionsAndUnauthorizedRequestersBeforeRecipientLookup() {
        NotificationRecipientResolutionRequest unsupported = new NotificationRecipientResolutionRequest(
                "v1", UUID.randomUUID(), "booking-service", "BookingRequested", 1,
                UUID.randomUUID(), UUID.randomUUID(), false, "SCOPED_ADMIN_REQUEST");
        assertThatThrownBy(() -> service.resolve(unsupported))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        UUID requester = UUID.randomUUID();
        when(authorization.authorizedNotificationExtensionRequestBuilding(requester)).thenReturn(null);
        assertThatThrownBy(() -> service.resolve(
                request(UUID.randomUUID(), "ExtensionRequested", requester, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(userRoles, never()).findActiveAdminUserIdsByBuildingId(org.mockito.ArgumentMatchers.any());
    }

    private static NotificationRecipientResolutionRequest request(
            UUID eventId, String eventType, UUID requesterId, UUID departmentId, Boolean vipRoom) {
        return new NotificationRecipientResolutionRequest("v1", eventId, "booking-service", eventType,
                2, requesterId, departmentId, vipRoom, "SCOPED_ADMIN_REQUEST");
    }
}
