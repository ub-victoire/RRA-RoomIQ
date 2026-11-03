package rw.rra.roomiq.audit.integration;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import rw.rra.roomiq.common.web.CorrelationIdFilter;
import rw.rra.roomiq.common.web.DomainException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Component
public class IdentityAuditSearchAuthorizationClient {
    private final RestClient identityClient;

    @Autowired
    public IdentityAuditSearchAuthorizationClient(
            @Value("${roomiq.identity.url:http://localhost:8081}") String identityUrl,
            @Value("${roomiq.identity.connect-timeout:2s}") Duration connectTimeout,
            @Value("${roomiq.identity.read-timeout:5s}") Duration readTimeout) {
        this(RestClient.builder(), identityUrl, connectTimeout, readTimeout);
    }

    IdentityAuditSearchAuthorizationClient(RestClient.Builder builder, String identityUrl,
                                           Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        identityClient = builder.baseUrl(identityUrl).requestFactory(requestFactory).build();
    }

    public AuditSearchAuthorizationDecision authorize(String authorization, UUID authenticatedUserId) {
        return authorize(authorization, authenticatedUserId, MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    public AuditSearchAuthorizationDecision authorize(String authorization, UUID authenticatedUserId,
                                                       String correlationId) {
        if (!isBearerToken(authorization) || authenticatedUserId == null) {
            throw authenticationRequired();
        }

        try {
            RestClient.RequestHeadersSpec<?> request = identityClient.post()
                    .uri("/api/v1/internal/authorization/audit-search")
                    .header(HttpHeaders.AUTHORIZATION, authorization);
            if (correlationId != null && !correlationId.isBlank()) {
                request.header(CorrelationIdFilter.HEADER_NAME, correlationId);
            }

            AuditSearchAuthorizationDecision decision = request.retrieve()
                    .body(AuditSearchAuthorizationDecision.class);
            validateDecision(decision, authenticatedUserId);
            return decision;
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw authenticationRequired();
        } catch (HttpClientErrorException.Forbidden exception) {
            throw accessDenied();
        } catch (HttpClientErrorException exception) {
            throw unavailable();
        } catch (RestClientException exception) {
            throw unavailable();
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw unavailable();
        }
    }

    private static void validateDecision(AuditSearchAuthorizationDecision decision, UUID authenticatedUserId) {
        if (decision == null
                || !authenticatedUserId.equals(decision.actorUserId())
                || decision.accessScope() == null
                || decision.buildingIds() == null
                || decision.buildingIds().stream().anyMatch(id -> id == null)
                || new HashSet<>(decision.buildingIds()).size() != decision.buildingIds().size()) {
            throw unavailable();
        }
        if (decision.accessScope() == AuditSearchAuthorizationDecision.AccessScope.GLOBAL
                && !decision.buildingIds().isEmpty()) {
            throw unavailable();
        }
        if (decision.accessScope() == AuditSearchAuthorizationDecision.AccessScope.BUILDING_SCOPED
                && decision.buildingIds().isEmpty()) {
            throw unavailable();
        }
    }

    private static boolean isBearerToken(String authorization) {
        return authorization != null
                && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                && !authorization.substring(7).isBlank()
                && authorization.substring(7).chars().noneMatch(Character::isWhitespace);
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
}
