package rw.rra.roomiq.lifecycle.security;

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
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import rw.rra.roomiq.booking.config.BookingSourceHoldAuthorizationInterceptor;
import rw.rra.roomiq.booking.integration.BookingSourceEvidenceTokenProvider;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.lifecycle.security.RecordsHoldServiceTokenProvider;
import rw.rra.roomiq.lifecycle.security.BookingSourceEvidenceJwtDecoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingRlcInteroperabilityTests {
    private static final String ISSUER = "https://issuer.example.test";
    private static final String KEY_ID = "test-key";

    @TempDir
    Path directory;
    private KeyPair keys;
    private String privateKey;
    private String publicKey;
    private BookingSourceEvidenceJwtDecoder evidenceDecoder;
    private BookingSourceHoldAuthorizationInterceptor holdVerifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        privateKey = writePem("private.pem", "PRIVATE KEY", keys.getPrivate().getEncoded());
        publicKey = writePem("public.pem", "PUBLIC KEY", keys.getPublic().getEncoded());
        var resources = new DefaultResourceLoader();
        evidenceDecoder = new BookingSourceEvidenceJwtDecoder(resources, ISSUER, KEY_ID, publicKey, "", "");
        holdVerifier = new BookingSourceHoldAuthorizationInterceptor(resources, ISSUER, KEY_ID, publicKey, "", "");
    }

    @Test
    void realBookingSignerProducesTokenAcceptedByRlc() {
        assertThat(BookingSourceEvidenceTokenProvider.SUBJECT)
                .isEqualTo(BookingSourceEvidenceJwtDecoder.SUBJECT);
        assertThat(BookingSourceEvidenceTokenProvider.AUDIENCE)
                .isEqualTo(BookingSourceEvidenceJwtDecoder.AUDIENCE);
        assertThat(BookingSourceEvidenceTokenProvider.PERMISSION)
                .isEqualTo(BookingSourceEvidenceJwtDecoder.PERMISSION);
        var signer = new BookingSourceEvidenceTokenProvider(new DefaultResourceLoader(), ISSUER, KEY_ID, privateKey);
        var jwt = evidenceDecoder.decode(signer.createAuthorizationHeader().substring(7));
        assertThat(jwt.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(jwt.getSubject()).isEqualTo(BookingSourceEvidenceTokenProvider.SUBJECT);
        assertThat(jwt.getAudience()).containsExactly(BookingSourceEvidenceTokenProvider.AUDIENCE);
        assertThat(jwt.getClaimAsStringList("permissions"))
                .containsExactly(BookingSourceEvidenceTokenProvider.PERMISSION);
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void realRlcSignerProducesTokenAcceptedByBooking() {
        assertThat(RecordsHoldServiceTokenProvider.SUBJECT)
                .isEqualTo(BookingSourceHoldAuthorizationInterceptor.SUBJECT);
        assertThat(RecordsHoldServiceTokenProvider.AUDIENCE)
                .isEqualTo(BookingSourceHoldAuthorizationInterceptor.AUDIENCE);
        assertThat(RecordsHoldServiceTokenProvider.PERMISSION)
                .isEqualTo(BookingSourceHoldAuthorizationInterceptor.PERMISSION);
        var signer = new RecordsHoldServiceTokenProvider(new DefaultResourceLoader(), "https://booking.example.test",
                Duration.ofSeconds(2), Duration.ofSeconds(5), ISSUER, KEY_ID, privateKey);
        String authorization = signer.createAuthorizationHeader();
        var decoder = org.springframework.security.oauth2.jwt.NimbusJwtDecoder
                .withPublicKey((RSAPublicKey) keys.getPublic()).build();
        var token = decoder.decode(authorization.substring(7));
        assertThat(token.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(token.getIssuedAt()).isNotNull();
        assertThat(token.getExpiresAt()).isNotNull();
        assertThat(Duration.between(token.getIssuedAt(), token.getExpiresAt()))
                .isEqualTo(Duration.ofMinutes(2));
        MockHttpServletRequest request = request(authorization);
        assertThat(holdVerifier.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(request.getAttribute(BookingSourceHoldAuthorizationInterceptor.AUTHENTICATED_SOURCE))
                .isEqualTo(RecordsHoldServiceTokenProvider.SUBJECT);
    }

    @Test
    void bothReceiversRejectUntrustedClaimsAndKeys() {
        Instant now = Instant.now();
        for (boolean evidence : List.of(true, false)) {
            String subject = evidence ? BookingSourceEvidenceTokenProvider.SUBJECT
                    : RecordsHoldServiceTokenProvider.SUBJECT;
            String audience = evidence ? BookingSourceEvidenceTokenProvider.AUDIENCE
                    : RecordsHoldServiceTokenProvider.AUDIENCE;
            String permission = evidence ? BookingSourceEvidenceTokenProvider.PERMISSION
                    : RecordsHoldServiceTokenProvider.PERMISSION;
            assertRejected(evidence, signed("https://untrusted.example.test", subject, audience,
                    permission, KEY_ID, now, now.plusSeconds(60)));
            assertRejected(evidence, signed(subject, "wrong-audience", permission, KEY_ID, now, now.plusSeconds(60)));
            assertRejected(evidence, signed(subject, audience, permission, KEY_ID,
                    now.minusSeconds(120), now.minusSeconds(1)));
            assertRejected(evidence, signed(subject, audience, permission, "wrong-key", now, now.plusSeconds(60)));
            assertRejected(evidence, signed(subject, audience, null, KEY_ID, now, now.plusSeconds(60)));
            assertRejected(evidence, signed("wrong-service", audience, permission, KEY_ID, now, now.plusSeconds(60)));
            assertRejected(evidence, signed(subject, audience, permission, KEY_ID, now, now.plusSeconds(121)));
        }
    }

    private void assertRejected(boolean evidence, String token) {
        if (evidence) {
            assertThatThrownBy(() -> evidenceDecoder.decode(token)).isInstanceOf(JwtException.class);
        } else {
            assertThatThrownBy(() -> holdVerifier.preHandle(
                    request("Bearer " + token), new MockHttpServletResponse(), new Object()))
                    .isInstanceOf(DomainException.class);
        }
    }

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", authorization);
        return request;
    }

    private String signed(String subject, String audience, String permission, String keyId,
                          Instant issuedAt, Instant expiresAt) {
        return signed(ISSUER, subject, audience, permission, keyId, issuedAt, expiresAt);
    }

    private String signed(String issuer, String subject, String audience, String permission, String keyId,
                          Instant issuedAt, Instant expiresAt) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keys.getPublic())
                .privateKey((RSAPrivateKey) keys.getPrivate()).keyID(keyId).build();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(subject).audience(List.of(audience))
                .issuedAt(issuedAt).expiresAt(expiresAt).id(UUID.randomUUID().toString());
        if (permission != null) {
            claims.claim("permissions", List.of(permission));
        }
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(jwk)))
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build(), claims.build()))
                .getTokenValue();
    }

    private String writePem(String filename, String type, byte[] key) throws Exception {
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key);
        Path path = directory.resolve(filename);
        Files.writeString(path, "-----BEGIN " + type + "-----\n" + encoded + "\n-----END " + type + "-----\n");
        return path.toUri().toString();
    }
}
