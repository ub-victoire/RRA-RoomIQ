package rw.rra.roomiq.notification.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import rw.rra.roomiq.common.web.CorrelationIdFilter;
import rw.rra.roomiq.common.web.DomainException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationIdentityAuthenticationClientTests {
    private static final String AUTHORIZATION = "Bearer current-identity-session";

    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> forwardedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> forwardedCorrelationId = new AtomicReference<>();
    private HttpServer server;
    private volatile int responseStatus = 200;
    private volatile String responseBody;
    private NotificationIdentityAuthenticationClient client;
    private UUID identityUserId;

    @BeforeEach
    void startIdentityStub() throws Exception {
        identityUserId = UUID.randomUUID();
        responseBody = "{\"actorUserId\":\"" + identityUserId + "\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/authorization/booking", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            forwardedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            forwardedCorrelationId.set(exchange.getRequestHeaders().getFirst(CorrelationIdFilter.HEADER_NAME));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.createContext("/api/v1/internal/authorization/notification-templates", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            forwardedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            forwardedCorrelationId.set(exchange.getRequestHeaders().getFirst(CorrelationIdFilter.HEADER_NAME));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.createContext("/api/v1/internal/authorization/notification-delivery", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            forwardedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            forwardedCorrelationId.set(exchange.getRequestHeaders().getFirst(CorrelationIdFilter.HEADER_NAME));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.start();
        client = new NotificationIdentityAuthenticationClient(
                org.springframework.web.client.RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopIdentityStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void forwardsCallerBearerToIdentityAuthenticateActionAndUsesItsActiveSubject() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "inbox-correlation");
        try {
            assertThat(client.authenticate(AUTHORIZATION)).isEqualTo(identityUserId);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
        assertThat(forwardedAuthorization.get()).isEqualTo(AUTHORIZATION);
        assertThat(forwardedCorrelationId.get()).isEqualTo("inbox-correlation");
        assertThat(requestBody.get()).contains("\"action\":\"AUTHENTICATE\"");
        assertThat(requestBody.get()).doesNotContain("recipientUserId", "notificationId");
    }

    @Test
    void requiresBearerTokenAndRejectsAccountsIdentityDoesNotAuthorize() {
        assertThatThrownBy(() -> client.authenticate(null))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);

        responseStatus = 403;
        responseBody = "{\"code\":\"ACCESS_DENIED\"}";

        assertThatThrownBy(() -> client.authenticate(AUTHORIZATION))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void failsClosedWhenIdentityCannotBeReached() {
        responseStatus = 503;
        responseBody = "{\"code\":\"UNAVAILABLE\"}";

        assertThatThrownBy(() -> client.authenticate(AUTHORIZATION))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.SERVICE_UNAVAILABLE)
                .hasMessageContaining("Identity");
    }

    @Test
    void forwardsAdminAuthorizationAndPreservesDeniedDecision() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "template-correlation");
        try {
            assertThat(client.authorizeNotificationTemplateManagement(AUTHORIZATION)).isEqualTo(identityUserId);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
        assertThat(requestPath.get()).isEqualTo("/api/v1/internal/authorization/notification-templates");
        assertThat(forwardedAuthorization.get()).isEqualTo(AUTHORIZATION);
        assertThat(forwardedCorrelationId.get()).isEqualTo("template-correlation");

        responseStatus = 403;
        assertThatThrownBy(() -> client.authorizeNotificationTemplateManagement(AUTHORIZATION))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void forwardsCallerAuthorizationToDedicatedDeliveryPermissionCheck() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "delivery-correlation");
        try {
            assertThat(client.authorizeNotificationDeliveryManagement(AUTHORIZATION)).isEqualTo(identityUserId);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
        assertThat(requestPath.get()).isEqualTo("/api/v1/internal/authorization/notification-delivery");
        assertThat(forwardedAuthorization.get()).isEqualTo(AUTHORIZATION);
        assertThat(forwardedCorrelationId.get()).isEqualTo("delivery-correlation");

        responseStatus = 403;
        assertThatThrownBy(() -> client.authorizeNotificationDeliveryManagement(AUTHORIZATION))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }
}
