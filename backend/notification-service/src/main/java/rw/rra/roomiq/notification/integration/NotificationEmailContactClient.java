package rw.rra.roomiq.notification.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.UUID;

@Component
public class NotificationEmailContactClient {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$");
    private final RestClient restClient;
    private final NotificationServiceTokenProvider tokenProvider;

    public NotificationEmailContactClient(NotificationServiceTokenProvider tokenProvider,
                                           @Value("${roomiq.identity.url:http://localhost:8081}") String identityUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().baseUrl(identityUrl).requestFactory(requestFactory).build();
        this.tokenProvider = tokenProvider;
    }

    public String resolveActiveAddress(UUID userId) {
        String serviceAuthorization;
        try {
            serviceAuthorization = tokenProvider.createAuthorizationHeader(
                    NotificationServiceTokenProvider.EMAIL_PERMISSION);
        } catch (ResponseStatusException exception) {
            throw new NotificationEmailContactException("IDENTITY_SERVICE_AUTH_CONFIGURATION_INVALID", false);
        }
        try {
            ApiResponse response = restClient.post()
                    .uri("/api/v1/internal/notifications/delivery-contact")
                    .header(NotificationRecipientResolutionHeader.SERVICE_AUTHORIZATION, serviceAuthorization)
                    .body(new Request(userId))
                    .retrieve()
                    .body(ApiResponse.class);
            if (response == null || !response.success() || response.data() == null
                    || !userId.equals(response.data().userId())
                    || response.data().email() == null || response.data().email().isBlank()) {
                throw new NotificationEmailContactException("RECIPIENT_CONTACT_INVALID", false);
            }
            if (!EMAIL.matcher(response.data().email()).matches()) {
                throw new NotificationEmailContactException("RECIPIENT_ADDRESS_INVALID", false);
            }
            return response.data().email();
        } catch (HttpClientErrorException.NotFound exception) {
            throw new NotificationEmailContactException("RECIPIENT_CONTACT_NOT_FOUND", false);
        } catch (HttpClientErrorException exception) {
            throw new NotificationEmailContactException("IDENTITY_CONTACT_AUTHORIZATION_FAILED", false);
        } catch (HttpServerErrorException | ResourceAccessException exception) {
            throw new NotificationEmailContactException("IDENTITY_CONTACT_TEMPORARILY_UNAVAILABLE", true);
        } catch (ResponseStatusException exception) {
            HttpStatusCode status = exception.getStatusCode();
            throw new NotificationEmailContactException(
                    status.is5xxServerError() ? "IDENTITY_CONTACT_TEMPORARILY_UNAVAILABLE"
                            : "IDENTITY_CONTACT_RESOLUTION_FAILED",
                    status.is5xxServerError());
        } catch (RestClientException exception) {
            throw new NotificationEmailContactException("IDENTITY_CONTACT_TEMPORARILY_UNAVAILABLE", true);
        }
    }

    private record Request(UUID userId) {
    }

    private record ApiResponse(boolean success, Data data) {
    }

    private record Data(UUID userId, String email) {
    }

    private static final class NotificationRecipientResolutionHeader {
        private static final String SERVICE_AUTHORIZATION = "X-RoomIQ-Service-Authorization";
    }

    public static final class NotificationEmailContactException extends RuntimeException {
        private final String reasonCode;
        private final boolean retryable;

        public NotificationEmailContactException(String reasonCode, boolean retryable) {
            super(reasonCode);
            this.reasonCode = reasonCode;
            this.retryable = retryable;
        }

        public String reasonCode() {
            return reasonCode;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
