package rw.rra.roomiq.notification.integration;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import rw.rra.roomiq.common.web.CorrelationIdFilter;
import rw.rra.roomiq.notification.domain.event.InvalidNotificationEventException;
import rw.rra.roomiq.notification.domain.event.NotificationEvent;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class NotificationIdentityRecipientClient {
    private final RestClient restClient;
    private final NotificationServiceTokenProvider tokenProvider;

    public NotificationIdentityRecipientClient(
            NotificationServiceTokenProvider tokenProvider,
            @Value("${roomiq.identity.url:http://localhost:8081}") String identityUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().baseUrl(identityUrl).requestFactory(requestFactory).build();
        this.tokenProvider = tokenProvider;
    }

    public List<UUID> resolveScopedAdmins(NotificationEvent event) {
        var payload = event.payload();
        UUID requesterId = uuidFact(payload, "requesterUserId");
        UUID departmentId = null;
        Boolean vipRoom = null;
        if ("BookingRequested".equals(event.eventType())) {
            departmentId = uuidFact(payload, "departmentId");
            if (payload.get("vipRoom") == null || !payload.get("vipRoom").isBoolean()) {
                throw invalidEvent();
            }
            vipRoom = payload.get("vipRoom").booleanValue();
        }
        Request request = new Request("v1", event.eventId(), event.sourceService(), event.eventType(),
                event.eventVersion(), requesterId, departmentId, vipRoom, "SCOPED_ADMIN_REQUEST");
        String correlationId = event.correlationId();
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        }
        try {
            RestClient.RequestBodySpec requestSpec = restClient.post()
                    .uri("/api/v1/internal/notifications/recipient-resolution")
                    .header(NotificationRecipientResolutionHeader.SERVICE_AUTHORIZATION,
                            tokenProvider.createAuthorizationHeader())
                    .body(request);
            if (correlationId != null && !correlationId.isBlank()) {
                requestSpec.header(CorrelationIdFilter.HEADER_NAME, correlationId);
            }
            ApiResponse response = requestSpec.retrieve().body(ApiResponse.class);
            if (response == null || !response.success() || response.data() == null
                    || !"v1".equals(response.data().contractVersion())
                    || !event.eventId().equals(response.data().eventId())
                    || !event.eventType().equals(response.data().eventType())
                    || response.data().officeBuildingId() == null
                    || response.data().recipientUserIds() == null
                    || response.data().recipientUserIds().stream().anyMatch(Objects::isNull)
                    || response.data().recipientUserIds().stream().distinct().count()
                    != response.data().recipientUserIds().size()) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Identity returned invalid notification recipient data");
            }
            return List.copyOf(response.data().recipientUserIds());
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Identity rejected Notification Service authentication");
        } catch (HttpClientErrorException.Forbidden exception) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Identity denied notification recipient resolution");
        } catch (RestClientException exception) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Identity recipient resolution is unavailable");
        }
    }

    private static UUID uuidFact(tools.jackson.databind.JsonNode payload, String field) {
        if (payload == null || !payload.has(field) || !payload.get(field).isTextual()) {
            throw invalidEvent();
        }
        try {
            return UUID.fromString(payload.get(field).asText());
        } catch (IllegalArgumentException exception) {
            throw invalidEvent();
        }
    }

    private static InvalidNotificationEventException invalidEvent() {
        return new InvalidNotificationEventException("Notification event lacks valid recipient-routing context");
    }

    private record Request(String contractVersion, UUID eventId, String sourceService, String eventType,
                           int eventVersion, UUID requesterUserId, UUID departmentId, Boolean vipRoom,
                           String notificationRoute) {
    }

    private record ApiResponse(boolean success, Data data) {
    }

    private record Data(String contractVersion, UUID eventId, String eventType, UUID officeBuildingId,
                        List<UUID> recipientUserIds) {
    }

    private static final class NotificationRecipientResolutionHeader {
        private static final String SERVICE_AUTHORIZATION = "X-RoomIQ-Service-Authorization";
    }
}
