package rw.rra.roomiq.booking.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import rw.rra.roomiq.common.web.DomainException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Component
public class BookingSourceHoldAuthorizationInterceptor implements HandlerInterceptor {
    public static final String AUTHENTICATED_SOURCE = "bookingAuthenticatedHoldSource";
    private static final String SUBJECT = "records-lifecycle-service";
    private static final String AUDIENCE = "rra-roomiq-booking-legal-hold";
    private static final String PERMISSION = "BOOKING_SOURCE_HOLD_ENFORCE";
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(2);

    private final ResourceLoader resourceLoader;
    private final String issuer;
    private final String currentKeyId;
    private final String currentKeyLocation;
    private final String previousKeyId;
    private final String previousKeyLocation;

    public BookingSourceHoldAuthorizationInterceptor(
            ResourceLoader resourceLoader,
            @Value("${roomiq.auth.records-lifecycle-service-token.issuer:}") String issuer,
            @Value("${roomiq.auth.records-lifecycle-service-token.current-key-id:}") String currentKeyId,
            @Value("${roomiq.auth.records-lifecycle-service-token.current-public-key-location:}")
            String currentKeyLocation,
            @Value("${roomiq.auth.records-lifecycle-service-token.previous-key-id:}") String previousKeyId,
            @Value("${roomiq.auth.records-lifecycle-service-token.previous-public-key-location:}")
            String previousKeyLocation) {
        this.resourceLoader = resourceLoader;
        this.issuer = issuer;
        this.currentKeyId = currentKeyId;
        this.currentKeyLocation = currentKeyLocation;
        this.previousKeyId = previousKeyId;
        this.previousKeyLocation = previousKeyLocation;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)
                || header.substring(7).isBlank() || header.substring(7).contains(" ")) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "SERVICE_AUTHENTICATION_REQUIRED",
                    "Trusted RLC service authentication is required");
        }
        Jwt jwt;
        try {
            jwt = decoder(header.substring(7)).decode(header.substring(7));
        } catch (JwtException exception) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "SERVICE_TOKEN_INVALID",
                    "Trusted RLC service authentication is invalid");
        }
        if (!SUBJECT.equals(jwt.getSubject()) || !jwt.getAudience().equals(List.of(AUDIENCE))
                || jwt.getIssuedAt() == null || jwt.getExpiresAt() == null
                || jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(30))
                || !jwt.getExpiresAt().isAfter(Instant.now())
                || jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plus(MAX_LIFETIME))
                || !isUuid(jwt.getId()) || !hasPermission(jwt)) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "SERVICE_TOKEN_INVALID",
                    "Trusted RLC service token claims are invalid");
        }
        request.setAttribute(AUTHENTICATED_SOURCE, SUBJECT);
        return true;
    }

    private JwtDecoder decoder(String token) {
        if (issuer.isBlank() || currentKeyId.isBlank() || currentKeyLocation.isBlank()) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_IDENTITY_UNCONFIGURED",
                    "RLC service identity is not configured");
        }
        String keyId = tokenKeyId(token);
        String location = currentKeyId.equals(keyId) ? currentKeyLocation
                : !previousKeyId.isBlank() && previousKeyId.equals(keyId) ? previousKeyLocation : null;
        if (location == null || location.isBlank()) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "SERVICE_TOKEN_KEY_UNTRUSTED",
                    "RLC service signing key is not trusted");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(readPublicKey(location))
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    private RSAPublicKey readPublicKey(String location) {
        try {
            Resource resource = resourceLoader.getResource(location);
            String pem;
            try (var input = resource.getInputStream()) {
                pem = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
            }
            String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (IOException | java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_TOKEN_KEY_UNAVAILABLE",
                    "RLC service verification key is unavailable");
        }
    }

    private static boolean hasPermission(Jwt jwt) {
        Object value = jwt.getClaims().get("permissions");
        return value instanceof Collection<?> values && values.contains(PERMISSION);
    }

    private static String tokenKeyId(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new BadJwtException("Service token is malformed");
        }
        try {
            var header = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"RS256".equals(header.path("alg").asText())
                    || !header.path("kid").isTextual()) {
                throw new BadJwtException("Service token header is invalid");
            }
            return header.path("kid").asText();
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new BadJwtException("Service token header is malformed", exception);
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return value != null;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }
}
