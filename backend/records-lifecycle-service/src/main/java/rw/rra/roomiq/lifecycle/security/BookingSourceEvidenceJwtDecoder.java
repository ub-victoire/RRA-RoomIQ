package rw.rra.roomiq.lifecycle.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Component
public class BookingSourceEvidenceJwtDecoder implements JwtDecoder {
    public static final String AUDIENCE = "rra-roomiq-records-lifecycle-source-evidence";
    public static final String PERMISSION = "BOOKING_SOURCE_EVIDENCE_PUBLISH";
    private static final String SUBJECT = "booking-service";
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(2);

    private final ResourceLoader resourceLoader;
    private final String issuer;
    private final String currentKeyId;
    private final String currentPublicKeyLocation;
    private final String previousKeyId;
    private final String previousPublicKeyLocation;

    public BookingSourceEvidenceJwtDecoder(
            ResourceLoader resourceLoader,
            @Value("${roomiq.auth.booking-service-token.issuer:}") String issuer,
            @Value("${roomiq.auth.booking-service-token.current-key-id:}") String currentKeyId,
            @Value("${roomiq.auth.booking-service-token.current-public-key-location:}") String currentPublicKeyLocation,
            @Value("${roomiq.auth.booking-service-token.previous-key-id:}") String previousKeyId,
            @Value("${roomiq.auth.booking-service-token.previous-public-key-location:}") String previousPublicKeyLocation) {
        this.resourceLoader = resourceLoader;
        this.issuer = issuer;
        this.currentKeyId = currentKeyId;
        this.currentPublicKeyLocation = currentPublicKeyLocation;
        this.previousKeyId = previousKeyId;
        this.previousPublicKeyLocation = previousPublicKeyLocation;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        if (issuer.isBlank() || currentKeyId.isBlank() || currentPublicKeyLocation.isBlank()) {
            throw new BadJwtException("Booking service identity is not configured");
        }
        String keyId = tokenKeyId(token);
        String keyLocation = keyLocationFor(keyId);
        if (keyLocation == null) {
            throw new BadJwtException("Booking service signing key is not trusted");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(readPublicKey(keyLocation))
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        Jwt jwt = decoder.decode(token);

        Instant now = Instant.now();
        Instant issuedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();
        if (!SUBJECT.equals(jwt.getSubject())
                || !jwt.getAudience().equals(List.of(AUDIENCE))
                || issuedAt == null || expiresAt == null
                || issuedAt.isAfter(now.plusSeconds(30))
                || !expiresAt.isAfter(now)
                || expiresAt.isAfter(issuedAt.plus(MAX_LIFETIME))
                || !isUuid(jwt.getId())
                || !hasIngestionPermission(jwt)) {
            throw new BadJwtException("Booking service token claims are invalid");
        }
        return jwt;
    }

    private static boolean hasIngestionPermission(Jwt jwt) {
        Object permissions = jwt.getClaims().get("permissions");
        return permissions instanceof Collection<?> values && values.contains(PERMISSION);
    }

    private String keyLocationFor(String keyId) {
        if (currentKeyId.equals(keyId)) {
            return currentPublicKeyLocation;
        }
        if (!previousKeyId.isBlank() && previousKeyId.equals(keyId)
                && !previousPublicKeyLocation.isBlank()) {
            return previousPublicKeyLocation;
        }
        return null;
    }

    private RSAPublicKey readPublicKey(String location) {
        try {
            Resource resource = resourceLoader.getResource(location);
            String pem;
            try (var input = resource.getInputStream()) {
                pem = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
            }
            String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (IOException | java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new BadJwtException("Booking service verification key is unavailable", exception);
        }
    }

    private static String tokenKeyId(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new BadJwtException("Bearer token is malformed");
        }
        try {
            JsonNode header = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode keyId = header.get("kid");
            JsonNode algorithm = header.get("alg");
            if (keyId == null || !keyId.isTextual() || algorithm == null
                    || !"RS256".equals(algorithm.asText())) {
                throw new BadJwtException("Bearer token header is invalid");
            }
            return keyId.asText();
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException exception) {
            throw new BadJwtException("Bearer token header is malformed", exception);
        }
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
