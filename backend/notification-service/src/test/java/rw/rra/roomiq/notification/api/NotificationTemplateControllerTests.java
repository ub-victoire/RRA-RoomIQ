package rw.rra.roomiq.notification.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rw.rra.roomiq.notification.domain.dto.NotificationTemplateActivationRequest;
import rw.rra.roomiq.notification.domain.dto.NotificationTemplateCreateRequest;
import rw.rra.roomiq.notification.domain.dto.NotificationTemplateResponse;
import rw.rra.roomiq.notification.domain.service.NotificationTemplateService;
import rw.rra.roomiq.notification.integration.NotificationIdentityAuthenticationClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationTemplateControllerTests {
    private static final String AUTHORIZATION = "Bearer active-user-token";

    private final NotificationTemplateService templateService = mock(NotificationTemplateService.class);
    private final NotificationIdentityAuthenticationClient identityClient =
            mock(NotificationIdentityAuthenticationClient.class);
    private final NotificationTemplateController controller =
            new NotificationTemplateController(templateService, identityClient);

    @BeforeEach
    void authorizeCaller() {
        when(identityClient.authorizeNotificationTemplateManagement(AUTHORIZATION))
                .thenReturn(UUID.randomUUID());
    }

    @Test
    void listRequiresIdentityAuthorizationBeforeReturningTemplates() {
        NotificationTemplateResponse template = template("BookingApproved", "IN_APP");
        when(templateService.list()).thenReturn(List.of(template));

        var response = controller.list(AUTHORIZATION);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).containsExactly(template);
        verify(identityClient).authorizeNotificationTemplateManagement(AUTHORIZATION);
        verify(templateService).list();
    }

    @Test
    void createUsesIdentityAuthorizedActorAndSharedSuccessEnvelope() {
        UUID actorId = UUID.randomUUID();
        NotificationTemplateCreateRequest request =
                new NotificationTemplateCreateRequest("BookingApproved", 1, "IN_APP", null, "Approved");
        NotificationTemplateResponse created = template("BookingApproved", "IN_APP");
        when(identityClient.authorizeNotificationTemplateManagement(AUTHORIZATION)).thenReturn(actorId);
        when(templateService.create(request, actorId)).thenReturn(created);

        var response = controller.create(AUTHORIZATION, request);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(created);
        verify(templateService).create(request, actorId);
    }

    @Test
    void activationUsesIdentityAuthorizedActor() {
        UUID actorId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        NotificationTemplateActivationRequest request = new NotificationTemplateActivationRequest(true);
        NotificationTemplateResponse active = template("BookingApproved", "IN_APP");
        when(identityClient.authorizeNotificationTemplateManagement(AUTHORIZATION)).thenReturn(actorId);
        when(templateService.setActive(templateId, request, actorId)).thenReturn(active);

        var response = controller.setActive(AUTHORIZATION, templateId, request);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(active);
        verify(templateService).setActive(templateId, request, actorId);
    }

    @Test
    void deniedIdentityAuthorizationPreventsTemplateServiceAccess() {
        when(identityClient.authorizeNotificationTemplateManagement(AUTHORIZATION))
                .thenThrow(new IllegalStateException("Template administration denied"));

        assertThatThrownBy(() -> controller.list(AUTHORIZATION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Template administration denied");
        verifyNoInteractions(templateService);
    }

    private static NotificationTemplateResponse template(String code, String channel) {
        return new NotificationTemplateResponse(UUID.randomUUID(), code, 1, channel, null, "Body", true);
    }
}
