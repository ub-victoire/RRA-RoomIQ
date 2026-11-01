package rw.rra.roomiq.identity.domain.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
public class NotificationServiceTokenVerifier {
    private static final String REQUIRED_SUBJECT = "notification-service";
    private static final String REQUIRED_PERMISSION = "notification:recipient-resolution";
    private static final Duration MAX_TOKEN_LIFETIME = Duration.ofMinutes(2);

    private final ResourceLoader resourceLoader;
    private final String issuer;
    private final String audience;
    private final String currentKeyId;
    private final String currentPublicKeyLocation;
    private final String previousKeyId;
    private final String previousPublicKeyLocation;

    public NotificationServiceTokenVerifier(
            ResourceLoader resourceLoader,
            @Value("${roomiq.auth.service-token.issuer:}") String issuer,
            @Value("${roomiq.auth.service-token.notification-audience:rra-roomiq-identity-recipient-resolution}") String audience,
            @Value("${roomiq.auth.service-token.notification-current-key-id:}") String currentKeyId,
            @Value("${roomiq.auth.service-token.notification-current-public-key-location:}") String currentPublicKeyLocation,
            @Value("${roomiq.auth.service-token.notification-previous-key-id:}") String previousKeyId,
            @Value("${roomiq.auth.service-token.notification-previous-public-key-location:}") String previousPublicKeyLocation) {
        this.resourceLoader = resourceLoader;
        this.issuer = issuer;
        this.audience = audience;
        this.currentKeyId = currentKeyId;
        this.currentPublicKeyLocation = currentPublicKeyLocation;
        this.previousKeyId = previousKeyId;
        this.previousPublicKeyLocation = previousPublicKeyLocation;
    }

    public void verify(String authorizationHeader) {
        if (issuer.isBlank() || audience.isBlank() || currentKeyId.isBlank()
                || currentPublicKeyLocation.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Notification service authentication is not configured");
        }
        String token = bearerToken(authorizationHeader);
        Jwt jwt;
        try {
            String keyId = tokenKeyId(token);
            String keyLocation = keyLocationFor(keyId);
            if (keyLocation == null) {
                throw unauthorized();
            }
            JwtDecoder decoder = NimbusJwtDecoder.withPublicKey(readPublicKey(keyLocation))
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            jwt = decoder.decode(token);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (JwtException exception) {
            throw unauthorized();
        }

        Instant now = Instant.now();
        Instant issuedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();
        if (!issuer.equals(jwt.getIssuer() == null ? null : jwt.getIssuer().toString())
                || !jwt.getAudience().contains(audience)
                || !REQUIRED_SUBJECT.equals(jwt.getSubject())
                || issuedAt == null || expiresAt == null
                || issuedAt.isAfter(now.plusSeconds(30))
                || !expiresAt.isAfter(now)
                || expiresAt.isAfter(issuedAt.plus(MAX_TOKEN_LIFETIME))
                || !isUuid(jwt.getId())) {
            throw unauthorized();
        }
        Object permissions = jwt.getClaims().get("permissions");
        if (!(permissions instanceof List<?> values) || !values.contains(REQUIRED_PERMISSION)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Notification service lacks recipient-resolution permission");
        }
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
            byte[] keyBytes = Base64.getDecoder().decode(encoded);
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (IOException | java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Notification service verification key is unavailable");
        }
    }

    private static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)
                || authorizationHeader.substring(7).isBlank()) {
            throw unauthorized();
        }
        return authorizationHeader.substring(7).trim();
    }

    private static String tokenKeyId(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw unauthorized();
        }
        try {
            tools.jackson.databind.JsonNode header = new tools.jackson.databind.ObjectMapper()
                    .readTree(Base64.getUrlDecoder().decode(parts[0]));
            tools.jackson.databind.JsonNode kid = header.get("kid");
            tools.jackson.databind.JsonNode alg = header.get("alg");
            if (kid == null || !kid.isTextual() || alg == null || !"RS256".equals(alg.asText())) {
                throw unauthorized();
            }
            return kid.asText();
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException exception) {
            throw unauthorized();
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

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Notification service authentication is invalid");
    }
}
