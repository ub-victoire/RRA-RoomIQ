package rw.rra.roomiq.notification.integration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationServiceTokenProviderTests {
    @Test
    void signsShortLivedServiceTokenWithApprovedClaimsAndExternalRsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        ResourceLoader loader = mock(ResourceLoader.class);
        when(loader.getResource("classpath:notification-private.pem"))
                .thenReturn(new ByteArrayResource(privateKeyPem((RSAPrivateKey) pair.getPrivate())));
        Instant now = Instant.now();
        NotificationServiceTokenProvider provider = new NotificationServiceTokenProvider(
                loader, "https://roomiq.example/identity",
                NotificationServiceTokenProvider.AUDIENCE, "notification-key-01",
                "classpath:notification-private.pem", Clock.fixed(now, ZoneOffset.UTC));

        String authorization = provider.createAuthorizationHeader();
        assertThat(authorization).startsWith("Bearer ");
        JwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) pair.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        Jwt jwt = decoder.decode(authorization.substring("Bearer ".length()));

        assertThat(jwt.getIssuer().toString()).isEqualTo("https://roomiq.example/identity");
        assertThat(jwt.getSubject()).isEqualTo("notification-service");
        assertThat(jwt.getAudience()).containsExactly(NotificationServiceTokenProvider.AUDIENCE);
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getHeaders().get("kid")).isEqualTo("notification-key-01");
        assertThat(jwt.getClaimAsStringList("permissions"))
                .containsExactly(NotificationServiceTokenProvider.PERMISSION);
        assertThat(jwt.getExpiresAt()).isEqualTo(now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(120));
    }

    @Test
    void refusesToSignWhenExternalServiceIdentityConfigurationIsIncomplete() {
        NotificationServiceTokenProvider provider = new NotificationServiceTokenProvider(
                mock(ResourceLoader.class), "", NotificationServiceTokenProvider.AUDIENCE, "", "",
                Clock.systemUTC());

        assertThatThrownBy(provider::createAuthorizationHeader)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private static byte[] privateKeyPem(RSAPrivateKey key) {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.getEncoded());
        return ("-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----")
                .getBytes(StandardCharsets.US_ASCII);
    }
}
