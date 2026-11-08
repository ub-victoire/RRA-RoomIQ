package rw.rra.roomiq.lifecycle.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import rw.rra.roomiq.common.web.DomainException;

import java.net.http.HttpClient;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

@Component
public class IdentityRetentionPolicyAuthorizationClient {
    private final RestClient identityClient;

    @Autowired
    public IdentityRetentionPolicyAuthorizationClient(
            @Value("${roomiq.identity.url}") String identityUrl,
            @Value("${roomiq.identity.connect-timeout:2s}") Duration connectTimeout,
            @Value("${roomiq.identity.read-timeout:5s}") Duration readTimeout,
            Environment environment) {
        this(RestClient.builder(), identityUrl, connectTimeout, readTimeout,
                environment.acceptsProfiles(Profiles.of("prod", "production")));
    }

    IdentityRetentionPolicyAuthorizationClient(RestClient.Builder builder, String identityUrl,
                                               Duration connectTimeout, Duration readTimeout) {
        this(builder, identityUrl, connectTimeout, readTimeout, false);
    }

    IdentityRetentionPolicyAuthorizationClient(RestClient.Builder builder, String identityUrl,
                                               Duration connectTimeout, Duration readTimeout,
                                               boolean production) {
        URI identityUri = validateIdentityUrl(identityUrl, production);
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalStateException("Identity connection timeouts must be positive");
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        identityClient = builder.baseUrl(identityUri.toString()).requestFactory(requestFactory).build();
    }

    public UUID authorize(String bearerToken, UUID authenticatedUserId, String correlationId) {
        if (!isBearerToken(bearerToken) || authenticatedUserId == null) {
            throw authenticationRequired();
        }
        try {
            RestClient.RequestHeadersSpec<?> request = identityClient.post()
                    .uri("/api/v1/internal/authorization/retention-policy")
                    .header(HttpHeaders.AUTHORIZATION, bearerToken);
            if (correlationId != null && !correlationId.isBlank()) {
                request.header("X-Correlation-ID", correlationId);
            }
            RetentionPolicyAuthorizationDecision decision =
                    request.retrieve().body(RetentionPolicyAuthorizationDecision.class);
            if (decision == null || !authenticatedUserId.equals(decision.actorUserId())) {
                throw unavailable();
            }
            return decision.actorUserId();
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw authenticationRequired();
        } catch (HttpClientErrorException.Forbidden exception) {
            throw accessDenied();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private static boolean isBearerToken(String value) {
        return value != null && value.regionMatches(true, 0, "Bearer ", 0, 7)
                && !value.substring(7).isBlank()
                && value.substring(7).chars().noneMatch(Character::isWhitespace);
    }

    private static URI validateIdentityUrl(String value, boolean production) {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute()
                    || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (production && !uri.getScheme().equalsIgnoreCase("https"))) {
                throw new IllegalStateException("IDENTITY_SERVICE_URL must be an absolute HTTP(S) URL"
                        + (production ? " using HTTPS in production" : ""));
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("IDENTITY_SERVICE_URL must be a valid absolute HTTP(S) URL", exception);
        }
    }

    private static DomainException authenticationRequired() {
        return new DomainException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                "Authentication is required");
    }

    private static DomainException accessDenied() {
        return new DomainException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied");
    }

    private static DomainException unavailable() {
        return new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_AUTHORIZATION_UNAVAILABLE",
                "Identity authorization is unavailable; the request cannot proceed");
    }

    private record RetentionPolicyAuthorizationDecision(UUID actorUserId) {
    }
}
