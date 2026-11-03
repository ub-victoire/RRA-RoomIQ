package rw.rra.roomiq.notification.integration;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.common.web.CorrelationIdFilter;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

@Component
public class NotificationIdentityAuthenticationClient {
    private final RestClient identityClient;

    @Autowired
    public NotificationIdentityAuthenticationClient(
            @Value("${roomiq.identity.url:http://localhost:8081}") String identityUrl) {
        this(timeoutBoundBuilder(RestClient.builder()), identityUrl);
    }

    NotificationIdentityAuthenticationClient(RestClient.Builder builder, String identityUrl) {
        identityClient = builder.baseUrl(identityUrl).build();
    }

    public UUID authenticate(String authorization) {
        if (!isBearerToken(authorization)) {
            throw authenticationRequired();
        }

        try {
            RestClient.RequestBodySpec request = identityClient.post()
                    .uri("/api/v1/internal/authorization/booking")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .contentType(MediaType.APPLICATION_JSON);
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null && !correlationId.isBlank()) {
                request.header(CorrelationIdFilter.HEADER_NAME, correlationId);
            }
            BookingAuthenticationResponse response = request.body(new BookingAuthenticationRequest("AUTHENTICATE"))
                    .retrieve()
                    .body(BookingAuthenticationResponse.class);
            if (response == null || response.actorUserId() == null) {
                throw unavailable();
            }
            return response.actorUserId();
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw authenticationRequired();
        } catch (HttpClientErrorException.Forbidden exception) {
            throw authenticationRequired();
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    public UUID authorizeNotificationTemplateManagement(String authorization) {
        if (!isBearerToken(authorization)) {
            throw authenticationRequired();
        }
        try {
            RestClient.RequestHeadersSpec<?> request = identityClient.post()
                    .uri("/api/v1/internal/authorization/notification-templates")
                    .header(HttpHeaders.AUTHORIZATION, authorization);
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null && !correlationId.isBlank()) {
                request.header(CorrelationIdFilter.HEADER_NAME, correlationId);
            }
            TemplateAuthorizationResponse response = request.retrieve()
                    .body(TemplateAuthorizationResponse.class);
            if (response == null || response.actorUserId() == null) {
                throw unavailable();
            }
            return response.actorUserId();
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw authenticationRequired();
        } catch (HttpClientErrorException.Forbidden exception) {
            throw new DomainException(HttpStatus.FORBIDDEN, "NOTIFICATION_TEMPLATE_MANAGEMENT_DENIED",
                    "Notification-template administration is denied");
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    public UUID authorizeNotificationDeliveryManagement(String authorization) {
        if (!isBearerToken(authorization)) {
            throw authenticationRequired();
        }
        try {
            RestClient.RequestHeadersSpec<?> request = identityClient.post()
                    .uri("/api/v1/internal/authorization/notification-delivery")
                    .header(HttpHeaders.AUTHORIZATION, authorization);
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null && !correlationId.isBlank()) {
                request.header(CorrelationIdFilter.HEADER_NAME, correlationId);
            }
            NotificationDeliveryAuthorizationResponse response = request.retrieve()
                    .body(NotificationDeliveryAuthorizationResponse.class);
            if (response == null || response.actorUserId() == null) {
                throw unavailable();
            }
            return response.actorUserId();
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw authenticationRequired();
        } catch (HttpClientErrorException.Forbidden exception) {
            throw new DomainException(HttpStatus.FORBIDDEN, "NOTIFICATION_DELIVERY_MANAGEMENT_DENIED",
                    "Notification-delivery management is denied");
        } catch (RestClientException exception) {
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
                "An active Identity account is required");
    }

    private static DomainException unavailable() {
        return new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_AUTHENTICATION_UNAVAILABLE",
                "Identity authentication is unavailable; the inbox operation cannot proceed");
    }

    private static RestClient.Builder timeoutBoundBuilder(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return builder.requestFactory(requestFactory);
    }

    private record BookingAuthenticationRequest(String action) {
    }

    private record BookingAuthenticationResponse(UUID actorUserId) {
    }

    private record TemplateAuthorizationResponse(UUID actorUserId) {
    }

    private record NotificationDeliveryAuthorizationResponse(UUID actorUserId) {
    }
}
