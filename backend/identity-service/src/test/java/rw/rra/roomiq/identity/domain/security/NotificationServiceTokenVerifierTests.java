package rw.rra.roomiq.identity.domain.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationServiceTokenVerifierTests {
    private static final String ISSUER = "https://roomiq.example/identity";
    private static final String AUDIENCE = "rra-roomiq-identity-recipient-resolution";
    private static final String PERMISSION = "notification:recipient-resolution";
    private final ResourceLoader resourceLoader = mock(ResourceLoader.class);
    private final KeyPair keyPair = newKeyPair();
    private final NotificationServiceTokenVerifier verifier = new NotificationServiceTokenVerifier(
            resourceLoader, ISSUER, AUDIENCE, "notification-current", "classpath:notification-public.pem",
            "", "");

    @BeforeEach
    void configurePublicKey() {
        when(resourceLoader.getResource("classpath:notification-public.pem"))
                .thenReturn(new ByteArrayResource(publicKeyPem((RSAPublicKey) keyPair.getPublic())));
    }

    @Test
    void acceptsShortLivedNotificationServiceTokenWithTheRequiredPermission() {
        verifier.verify("Bearer " + token(ISSUER, AUDIENCE, "notification-service", PERMISSION,
                "notification-current", Instant.now(), Instant.now().plusSeconds(90)));
    }

    @Test
    void rejectsWrongIssuerAudienceSubjectKeyAndExpiredTokens() {
        assertUnauthorized(token("https://other.example", AUDIENCE, "notification-service", PERMISSION,
                "notification-current", Instant.now(), Instant.now().plusSeconds(60)));
        assertUnauthorized(token(ISSUER, "wrong-audience", "notification-service", PERMISSION,
                "notification-current", Instant.now(), Instant.now().plusSeconds(60)));
        assertUnauthorized(token(ISSUER, AUDIENCE, "booking-service", PERMISSION,
                "notification-current", Instant.now(), Instant.now().plusSeconds(60)));
        assertUnauthorized(token(ISSUER, AUDIENCE, "notification-service", PERMISSION,
                "unknown-key", Instant.now(), Instant.now().plusSeconds(60)));
        assertUnauthorized(token(ISSUER, AUDIENCE, "notification-service", PERMISSION,
                "notification-current", Instant.now().minusSeconds(120), Instant.now().minusSeconds(60)));
    }

    @Test
    void rejectsMissingPermissionAndLifetimeOverTwoMinutes() {
        assertThatThrownBy(() -> verifier.verify("Bearer " + token(ISSUER, AUDIENCE, "notification-service",
                "notification:other", "notification-current", Instant.now(), Instant.now().plusSeconds(60))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        assertUnauthorized(token(ISSUER, AUDIENCE, "notification-service", PERMISSION,
                "notification-current", Instant.now(), Instant.now().plusSeconds(180)));
    }

    @Test
    void missingVerifierConfigurationFailsClosedAsUnavailable() {
        NotificationServiceTokenVerifier unconfigured = new NotificationServiceTokenVerifier(
                resourceLoader, "", AUDIENCE, "", "", "", "");

        assertThatThrownBy(() -> unconfigured.verify("Bearer token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private String token(String issuer, String audience, String subject, String permission, String keyId,
                         Instant issuedAt, Instant expiresAt) {
        RSAKey signingKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey(keyPair.getPrivate()).keyID(keyId).build();
        JwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableJWKSet<SecurityContext>(new JWKSet(signingKey)));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("permissions", List.of(permission))
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build(), claims)).getTokenValue();
    }

    private void assertUnauthorized(String token) {
        assertThatThrownBy(() -> verifier.verify("Bearer " + token))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] publicKeyPem(RSAPublicKey key) {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.getEncoded());
        return ("-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----")
                .getBytes(StandardCharsets.US_ASCII);
    }
}
