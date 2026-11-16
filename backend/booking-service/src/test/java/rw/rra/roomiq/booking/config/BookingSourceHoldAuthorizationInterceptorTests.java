package rw.rra.roomiq.booking.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import rw.rra.roomiq.common.web.DomainException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingSourceHoldAuthorizationInterceptorTests {
    private static final String ISSUER = "https://identity.test/rlc";
    private static final String SUBJECT = "records-lifecycle-service";
    private static final String AUDIENCE = "rra-roomiq-booking-legal-hold";
    private static final String PERMISSION = "BOOKING_SOURCE_HOLD_ENFORCE";

    @TempDir
    Path tempDirectory;
    private KeyPair trustedKey;
    private KeyPair untrustedKey;
    private BookingSourceHoldAuthorizationInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        trustedKey = keyPair();
        untrustedKey = keyPair();
        Path publicKeyPath = tempDirectory.resolve("rlc-public.pem");
        Files.writeString(publicKeyPath, "-----BEGIN PUBLIC KEY-----\n"
                + java.util.Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(trustedKey.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----");
        interceptor = new BookingSourceHoldAuthorizationInterceptor(new DefaultResourceLoader(),
                ISSUER, "trusted-key", "file:" + publicKeyPath, "", "");
    }

    @Test
    void acceptsValidTrustedServiceTokenAndSetsAuthenticatedIdentity() {
        MockHttpServletRequest request = request(token(trustedKey, ISSUER, List.of(AUDIENCE),
                SUBJECT, List.of(PERMISSION), Instant.now().plusSeconds(60)));

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(request.getAttribute(BookingSourceHoldAuthorizationInterceptor.AUTHENTICATED_SOURCE))
                .isEqualTo(SUBJECT);
    }

    @Test
    void rejectsInvalidSignatureExpiredWrongIssuerWrongAudienceMissingPermissionAndWrongService() {
        assertRejected(token(untrustedKey, ISSUER, List.of(AUDIENCE), SUBJECT, List.of(PERMISSION),
                Instant.now().plusSeconds(60)));
        assertRejected(token(trustedKey, ISSUER, List.of(AUDIENCE), SUBJECT, List.of(PERMISSION),
                Instant.now().minusSeconds(1)));
        assertRejected(token(trustedKey, "https://untrusted.test", List.of(AUDIENCE), SUBJECT,
                List.of(PERMISSION), Instant.now().plusSeconds(60)));
        assertRejected(token(trustedKey, ISSUER, List.of("another-audience"), SUBJECT,
                List.of(PERMISSION), Instant.now().plusSeconds(60)));
        assertRejected(token(trustedKey, ISSUER, List.of(), SUBJECT,
                List.of(PERMISSION), Instant.now().plusSeconds(60)));
        assertRejected(token(trustedKey, ISSUER, List.of(AUDIENCE), SUBJECT,
                List.of("UNRELATED_PERMISSION"), Instant.now().plusSeconds(60)));
        assertRejected(token(trustedKey, ISSUER, List.of(AUDIENCE), "booking-service",
                List.of(PERMISSION), Instant.now().plusSeconds(60)));
    }

    @Test
    void rejectsMalformedAuthorizationHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic unsupported");
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(DomainException.class);
    }

    private void assertRejected(String token) {
        MockHttpServletRequest request = request(token);
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(DomainException.class);
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private static String token(KeyPair keyPair, String issuer, List<String> audience,
                                String subject, List<String> permissions, Instant expiresAt) {
        RSAKey jwk = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
                .privateKey((java.security.interfaces.RSAPrivateKey) keyPair.getPrivate())
                .keyID("trusted-key").build();
        JwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableJWKSet<SecurityContext>(new JWKSet(jwk)));
        Instant now = Instant.now();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("trusted-key").build(),
                JwtClaimsSet.builder().issuer(issuer).subject(subject).audience(audience)
                        .issuedAt(now.minusSeconds(60)).expiresAt(expiresAt).id(UUID.randomUUID().toString())
                        .claim("permissions", permissions).build())).getTokenValue();
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
