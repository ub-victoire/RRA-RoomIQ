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

class AuditSourceEvidenceJwtDecoderTests {
    private static final String ISSUER = "https://audit-issuer.example.test";
    private static final String KEY_ID = "audit-test-key";

    @TempDir
    Path directory;
    private KeyPair trustedKeys;
    private KeyPair untrustedKeys;
    private String publicKeyLocation;
    private AuditSourceEvidenceJwtDecoder decoder;

    @BeforeEach
    void setUp() throws Exception {
        trustedKeys = keyPair();
        untrustedKeys = keyPair();
        publicKeyLocation = writePem("audit-public.pem", "PUBLIC KEY", trustedKeys.getPublic().getEncoded());
        decoder = new AuditSourceEvidenceJwtDecoder(new DefaultResourceLoader(), ISSUER,
                KEY_ID, publicKeyLocation, "", "");
    }

    @Test
    void acceptsValidAuditIdentityAndDedicatedPermission() {
        String token = signed(trustedKeys, ISSUER, AuditSourceEvidenceJwtDecoder.SUBJECT,
                AuditSourceEvidenceJwtDecoder.AUDIENCE, AuditSourceEvidenceJwtDecoder.PERMISSION,
                KEY_ID, Instant.now(), Instant.now().plusSeconds(120));

        var jwt = decoder.decode(token);

        assertThat(jwt.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(jwt.getSubject()).isEqualTo("audit-service");
        assertThat(jwt.getAudience()).containsExactly(AuditSourceEvidenceJwtDecoder.AUDIENCE);
    }

    @Test
    void rejectsInvalidSignatureIssuerAudienceExpiryPermissionAndSourceIdentity() {
        Instant now = Instant.now();
        assertRejected(signed(untrustedKeys, ISSUER, "audit-service",
                AuditSourceEvidenceJwtDecoder.AUDIENCE, AuditSourceEvidenceJwtDecoder.PERMISSION,
                KEY_ID, now, now.plusSeconds(60)));
        assertRejected(signed(trustedKeys, "https://untrusted.example.test", "audit-service",
                AuditSourceEvidenceJwtDecoder.AUDIENCE, AuditSourceEvidenceJwtDecoder.PERMISSION,
                KEY_ID, now, now.plusSeconds(60)));
        assertRejected(signed(trustedKeys, ISSUER, "audit-service", "wrong-audience",
                AuditSourceEvidenceJwtDecoder.PERMISSION, KEY_ID, now, now.plusSeconds(60)));
        assertRejected(signed(trustedKeys, ISSUER, "audit-service",
                AuditSourceEvidenceJwtDecoder.AUDIENCE, null, KEY_ID, now, now.plusSeconds(60)));
        assertRejected(signed(trustedKeys, ISSUER, "booking-service",
                AuditSourceEvidenceJwtDecoder.AUDIENCE, AuditSourceEvidenceJwtDecoder.PERMISSION,
                KEY_ID, now, now.plusSeconds(60)));
        assertRejected(signed(trustedKeys, ISSUER, "audit-service",
                AuditSourceEvidenceJwtDecoder.AUDIENCE, AuditSourceEvidenceJwtDecoder.PERMISSION,
                KEY_ID, now.minusSeconds(120), now.minusSeconds(1)));
        assertRejected("not-a-jwt");
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private static String signed(KeyPair keys, String issuer, String subject, String audience,
                                 String permission, String keyId, Instant issuedAt, Instant expiresAt) {
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

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String writePem(String filename, String type, byte[] key) throws Exception {
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key);
        Path path = directory.resolve(filename);
        Files.writeString(path, "-----BEGIN " + type + "-----\n" + encoded + "\n-----END " + type + "-----\n");
        return path.toUri().toString();
    }
}
