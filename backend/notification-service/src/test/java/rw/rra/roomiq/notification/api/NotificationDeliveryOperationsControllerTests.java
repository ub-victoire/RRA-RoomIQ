package rw.rra.roomiq.notification.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.notification.domain.dto.NotificationDeliveryHealthResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationRedriveResponse;
import rw.rra.roomiq.notification.domain.service.NotificationDeliveryOperationsService;
import rw.rra.roomiq.notification.integration.NotificationIdentityAuthenticationClient;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NotificationDeliveryOperationsControllerTests {
    private static final String AUTHORIZATION = "Bearer operator-token";
    private final NotificationDeliveryOperationsService operationsService =
            mock(NotificationDeliveryOperationsService.class);
    private final NotificationIdentityAuthenticationClient identityClient =
            mock(NotificationIdentityAuthenticationClient.class);
    private final NotificationDeliveryOperationsController controller =
            new NotificationDeliveryOperationsController(operationsService, identityClient);

    @Test
    void healthRequiresDedicatedIdentityAuthorization() {
        var health = new NotificationDeliveryHealthResponse(
                2, Instant.parse("2026-03-16T12:00:00Z"), 3, 1, 4, 5, 6, 12.5, 30.0);
        when(operationsService.health()).thenReturn(health);

        var response = controller.health(AUTHORIZATION);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(health);
        verify(identityClient).authorizeNotificationDeliveryManagement(AUTHORIZATION);
    }

    @Test
    void deniesOperationsWhenIdentityRejectsTheCaller() {
        when(identityClient.authorizeNotificationDeliveryManagement(AUTHORIZATION))
                .thenThrow(new DomainException(HttpStatus.FORBIDDEN,
                        "NOTIFICATION_DELIVERY_MANAGEMENT_DENIED", "Denied"));

        assertThatThrownBy(() -> controller.health(AUTHORIZATION))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
        verifyNoInteractions(operationsService);
    }

    @Test
    void redrivePassesOnlyIdentityActorAndIdempotencyKey() {
        UUID actorId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        var redrive = new NotificationRedriveResponse(
                notificationId, "SCHEDULED", Instant.parse("2026-03-16T12:00:00Z"), false);
        when(identityClient.authorizeNotificationDeliveryManagement(AUTHORIZATION)).thenReturn(actorId);
        when(operationsService.redrive(notificationId, "retry-1", actorId)).thenReturn(redrive);

        var response = controller.redrive(AUTHORIZATION, "retry-1", notificationId);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(redrive);
        verify(operationsService).redrive(notificationId, "retry-1", actorId);
    }
}
