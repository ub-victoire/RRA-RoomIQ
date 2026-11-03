package rw.rra.roomiq.notification.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NotificationEmailContactClientTests {
    @Test
    void missingServiceSigningConfigurationBecomesAPermanentContactFailure() {
        NotificationServiceTokenProvider tokenProvider = mock(NotificationServiceTokenProvider.class);
        when(tokenProvider.createAuthorizationHeader(NotificationServiceTokenProvider.EMAIL_PERMISSION)).thenThrow(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "private key detail"));
        NotificationEmailContactClient client = new NotificationEmailContactClient(
                tokenProvider, "http://localhost:8081");

        assertThatThrownBy(() -> client.resolveActiveAddress(UUID.randomUUID()))
                .isInstanceOf(NotificationEmailContactClient.NotificationEmailContactException.class)
                .hasMessage("IDENTITY_SERVICE_AUTH_CONFIGURATION_INVALID")
                .satisfies(exception -> assertThat(
                        ((NotificationEmailContactClient.NotificationEmailContactException) exception).retryable())
                        .isFalse());
    }
}
