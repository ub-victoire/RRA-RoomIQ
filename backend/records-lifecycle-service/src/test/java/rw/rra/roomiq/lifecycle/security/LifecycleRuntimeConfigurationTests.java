package rw.rra.roomiq.lifecycle.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import rw.rra.roomiq.common.web.DomainException;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleRuntimeConfigurationTests {
    @Test
    void identityUrlMustBeAbsoluteHttpUrlWithoutEmbeddedCredentialsOrQuery() {
        assertThatThrownBy(() -> client("localhost:8081", false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client("http://user:password@identity:8081", false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client("http://identity:8081?token=secret", false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client("http://identity:8081", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS in production");
    }

    @Test
    void identityUrlAndTimeoutsAreValidatedAtStartup() {
        assertThat(client("https://identity.internal", true)).isNotNull();
        assertThatThrownBy(() -> client("", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new IdentityRetentionPolicyAuthorizationClient(
                RestClient.builder(), "http://identity:8081", Duration.ZERO, Duration.ofSeconds(1), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timeouts must be positive");
    }

    @Test
    void identityConnectionFailureIsUnavailableAndFailsClosed() {
        IdentityRetentionPolicyAuthorizationClient unavailable = new IdentityRetentionPolicyAuthorizationClient(
                RestClient.builder(), "http://127.0.0.1:1", Duration.ofMillis(100),
                Duration.ofMillis(100), false);

        assertThatThrownBy(() -> unavailable.authorize("Bearer test-token", UUID.randomUUID(), "correlation"))
                .isInstanceOfSatisfying(DomainException.class, exception ->
                        assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void jwtSigningKeyRejectsMissingOrShortSecrets() {
        LifecycleSecurityConfiguration configuration = new LifecycleSecurityConfiguration();
        assertThatThrownBy(() -> configuration.lifecycleJwtSecretKey(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
        assertThatThrownBy(() -> configuration.lifecycleJwtSecretKey("too-short"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(configuration.lifecycleJwtDecoder(
                configuration.lifecycleJwtSecretKey("test-only-signing-key-at-least-32-bytes-long")))
                .isNotNull();
    }

    private static IdentityRetentionPolicyAuthorizationClient client(String url, boolean production) {
        return new IdentityRetentionPolicyAuthorizationClient(RestClient.builder(), url,
                Duration.ofSeconds(1), Duration.ofSeconds(1), production);
    }
}
