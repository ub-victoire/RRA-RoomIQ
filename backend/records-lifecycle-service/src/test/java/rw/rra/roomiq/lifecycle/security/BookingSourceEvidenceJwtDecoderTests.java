package rw.rra.roomiq.lifecycle.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingSourceEvidenceJwtDecoderTests {
    private static final String ISSUER = "roomiq-booking";
    private static final String KEY_ID = "booking-key-1";
    private KeyPair signingKey;
    private BookingSourceEvidenceJwtDecoder decoder;

    @TempDir
    Path tempDirectory;

    @BeforeEach
    void setUp() throws Exception {
        signingKey = generateKey();
        decoder = decoder(KEY_ID, signingKey, "", null);
    }

    @Test
    void acceptsTrustedBookingTokenWithDedicatedAudienceAndPermission() {
        String token = token(signingKey, ISSUER, BookingSourceEvidenceJwtDecoder.AUDIENCE,
                "booking-service", true, Instant.now(), Instant.now().plusSeconds(60), KEY_ID);

        assertThat(decoder.decode(token).getSubject()).isEqualTo("booking-service");
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        String token = token(generateKey(), ISSUER, BookingSourceEvidenceJwtDecoder.AUDIENCE,
                "booking-service", true, Instant.now(), Instant.now().plusSeconds(60), KEY_ID);

        assertRejected(token);
    }

    @Test
    void rejectsExpiredToken() {
        Instant now = Instant.now();
        assertRejected(token(signingKey, ISSUER, BookingSourceEvidenceJwtDecoder.AUDIENCE,
                "booking-service", true, now.minusSeconds(120), now.minusSeconds(1), KEY_ID));
    }

    @Test
    void rejectsWrongIssuer() {
        Instant now = Instant.now();
        assertRejected(token(signingKey, "untrusted-issuer", BookingSourceEvidenceJwtDecoder.AUDIENCE,
                "booking-service", true, now, now.plusSeconds(60), KEY_ID));
    }

    @Test
    void rejectsWrongOrAdditionalAudience() {
        Instant now = Instant.now();
        assertRejected(token(signingKey, ISSUER, "another-audience",
                "booking-service", true, now, now.plusSeconds(60), KEY_ID));
        assertRejected(token(signingKey, ISSUER,
                List.of(BookingSourceEvidenceJwtDecoder.AUDIENCE, "another-audience"),
                "booking-service", true, now, now.plusSeconds(60), KEY_ID));
    }

    @Test
    void rejectsMissingPermissionAndUnauthorizedSourceSubject() {
        Instant now = Instant.now();
        assertRejected(token(signingKey, ISSUER, BookingSourceEvidenceJwtDecoder.AUDIENCE,
                "booking-service", false, now, now.plusSeconds(60), KEY_ID));
        assertRejected(token(signingKey, ISSUER, BookingSourceEvidenceJwtDecoder.AUDIENCE,
                "notification-service", true, now, now.plusSeconds(60), KEY_ID));
    }

    @Test
    void rejectsTokenLifetimeLongerThanTheServiceTokenLimit() {
        Instant now = Instant.now();
        assertRejected(token(signingKey, ISSUER, BookingSourceEvidenceJwtDecoder.AUDIENCE,
                "booking-service", true, now, now.plusSeconds(121), KEY_ID));
    }

    @Test
    void acceptsPreviousKeyDuringConfiguredRotationWindow() throws Exception {
        KeyPair previousKey = generateKey();
        String previousLocation = writePublicKey(previousKey, "previous-key.pem");
        BookingSourceEvidenceJwtDecoder rotatingDecoder = new BookingSourceEvidenceJwtDecoder(
                new DefaultResourceLoader(), ISSUER, KEY_ID, writePublicKey(signingKey, "current-key.pem"),
                "booking-key-previous", previousLocation);
        Instant now = Instant.now();
        String token = token(previousKey, ISSUER, BookingSourceEvidenceJwtDecoder.AUDIENCE,
                "booking-service", true, now, now.plusSeconds(60), "booking-key-previous");

        assertThat(rotatingDecoder.decode(token).getSubject()).isEqualTo("booking-service");
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private BookingSourceEvidenceJwtDecoder decoder(String keyId, KeyPair keyPair,
                                                     String previousKeyId, KeyPair previousKey) throws Exception {
        String currentLocation = writePublicKey(keyPair, "current-key.pem");
        String previousLocation = previousKey == null ? "" : writePublicKey(previousKey, "previous-key.pem");
        return new BookingSourceEvidenceJwtDecoder(new DefaultResourceLoader(), ISSUER, keyId,
                currentLocation, previousKeyId, previousLocation);
    }

    private String writePublicKey(KeyPair keyPair, String fileName) throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(publicKey.getEncoded());
        Path path = tempDirectory.resolve(fileName);
        Files.writeString(path, "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n");
        return path.toUri().toString();
    }

    private static String token(KeyPair keyPair, String issuer, String audience, String subject,
                                boolean includePermission, Instant issuedAt, Instant expiresAt, String keyId) {
        return token(keyPair, issuer, List.of(audience), subject, includePermission, issuedAt, expiresAt, keyId);
    }

    private static String token(KeyPair keyPair, String issuer, List<String> audiences, String subject,
                                boolean includePermission, Instant issuedAt, Instant expiresAt, String keyId) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(keyId)
                .build();
        JwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableJWKSet<SecurityContext>(new JWKSet(jwk)));
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .audience(audiences)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString());
        if (includePermission) {
            claims.claim("permissions", List.of(BookingSourceEvidenceJwtDecoder.PERMISSION));
        }
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build(), claims.build())).getTokenValue();
    }

    private static KeyPair generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
