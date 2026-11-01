package rw.rra.roomiq.notification.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rw.rra.roomiq.notification.domain.event.NotificationEvent;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationIdentityRecipientClientTests {
    private final NotificationServiceTokenProvider tokenProvider = mock(NotificationServiceTokenProvider.class);
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> serviceAuthorization = new AtomicReference<>();
    private final AtomicReference<String> correlationId = new AtomicReference<>();
    private HttpServer server;
    private NotificationIdentityRecipientClient client;

    @BeforeEach
    void startIdentityStub() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/notifications/recipient-resolution", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            serviceAuthorization.set(exchange.getRequestHeaders().getFirst("X-RoomIQ-Service-Authorization"));
            correlationId.set(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
            byte[] response = """
                    {
                      "success": true,
                      "message": "Notification recipients resolved",
                      "data": {
                        "contractVersion": "v1",
                        "eventId": "%s",
                        "eventType": "BookingRequested",
                        "officeBuildingId": "%s",
                        "recipientUserIds": ["%s"]
                      },
                      "metadata": {},
                      "timestamp": "2026-10-12T12:00:00Z"
                    }
                    """.formatted(eventId, buildingId, adminId).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        when(tokenProvider.createAuthorizationHeader()).thenReturn("Bearer signed-service-jwt");
        client = new NotificationIdentityRecipientClient(tokenProvider,
                "http://127.0.0.1:" + server.getAddress().getPort());
        expectedResponseAdmin = adminId;
        expectedEventId = eventId;
        expectedBuildingId = buildingId;
    }

    private UUID expectedResponseAdmin;
    private UUID expectedEventId;
    private UUID expectedBuildingId;

    @AfterEach
    void stopIdentityStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsVersionedRoutingContextWithServiceAuthenticationAndValidatesIdentityResponse() {
        UUID requesterId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        NotificationEvent event = new NotificationEvent(expectedEventId, "booking-service", "BookingRequested",
                2, "BOOKING_REQUEST", UUID.randomUUID(), 1, "corr-routing", null,
                Instant.parse("2026-10-12T12:00:00Z"),
                new ObjectMapper().valueToTree(Map.of(
                        "requesterUserId", requesterId.toString(),
                        "departmentId", departmentId.toString(),
                        "vipRoom", false,
                        "notificationRoute", "SCOPED_ADMIN_REQUEST")),
                List.of(), "BOOKING_REQUEST", UUID.randomUUID());

        assertThat(client.resolveScopedAdmins(event)).containsExactly(expectedResponseAdmin);
        assertThat(serviceAuthorization.get()).isEqualTo("Bearer signed-service-jwt");
        assertThat(correlationId.get()).isEqualTo("corr-routing");
        assertThat(requestBody.get()).contains("\"contractVersion\":\"v1\"")
                .contains("\"notificationRoute\":\"SCOPED_ADMIN_REQUEST\"")
                .contains("\"requesterUserId\":\"" + requesterId + "\"")
                .contains("\"departmentId\":\"" + departmentId + "\"")
                .contains("\"vipRoom\":false")
                .contains("\"eventId\":\"" + expectedEventId + "\"")
                .contains("\"eventVersion\":2");
        assertThat(requestBody.get()).doesNotContain("recipientUserIds", "email", "displayName");
    }
}
