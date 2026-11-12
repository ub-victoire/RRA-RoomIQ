package rw.rra.roomiq.booking.integration;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
public class BookingSourceEvidenceTokenProvider {
    private static final String SUBJECT = "booking-service";
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(2);

    private final ResourceLoader resourceLoader;
    private final String issuer;
    private final String keyId;
    private final String privateKeyLocation;
    private final Clock clock;

    public BookingSourceEvidenceTokenProvider(
            ResourceLoader resourceLoader,
            @Value("${roomiq.booking.service-auth.issuer:}") String issuer,
            @Value("${roomiq.booking.service-auth.key-id:}") String keyId,
            @Value("${roomiq.booking.service-auth.private-key-location:}") String privateKeyLocation) {
        this(resourceLoader, issuer, keyId, privateKeyLocation, Clock.systemUTC());
    }

    BookingSourceEvidenceTokenProvider(ResourceLoader resourceLoader, String issuer, String keyId,
                                       String privateKeyLocation, Clock clock) {
        this.resourceLoader = resourceLoader;
        this.issuer = issuer;
        this.keyId = keyId;
        this.privateKeyLocation = privateKeyLocation;
        this.clock = clock;
    }

    public String createAuthorizationHeader() {
        if (issuer.isBlank() || keyId.isBlank() || privateKeyLocation.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Booking source-evidence identity is not configured");
        }
        try {
            RSAPrivateCrtKey privateKey = readPrivateKey();
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            RSAKey jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();
            JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(jwk)));
            Instant now = clock.instant();
            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .issuer(issuer)
                    .subject(SUBJECT)
                    .audience(List.of(BookingSourceEvidenceJwtAudience.VALUE))
                    .issuedAt(now)
                    .expiresAt(now.plus(TOKEN_LIFETIME))
                    .id(UUID.randomUUID().toString())
                    .claim("permissions", List.of(BookingSourceEvidencePermission.CODE))
                    .build();
            String token = encoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build(), claims)).getTokenValue();
            return "Bearer " + token;
        } catch (IOException | java.security.GeneralSecurityException | RuntimeException exception) {
            if (exception instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Booking source-evidence signing key is unavailable");
        }
    }

    private RSAPrivateCrtKey readPrivateKey() throws IOException, java.security.GeneralSecurityException {
        Resource resource = resourceLoader.getResource(privateKeyLocation);
        String pem;
        try (var input = resource.getInputStream()) {
            pem = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        }
        String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(encoded);
        RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
            throw new java.security.GeneralSecurityException("RSA private key must contain CRT parameters");
        }
        return crtKey;
    }

    private static final class BookingSourceEvidenceJwtAudience {
        private static final String VALUE = "rra-roomiq-records-lifecycle-source-evidence";
    }

    private static final class BookingSourceEvidencePermission {
        private static final String CODE = "BOOKING_SOURCE_EVIDENCE_PUBLISH";
    }
}
