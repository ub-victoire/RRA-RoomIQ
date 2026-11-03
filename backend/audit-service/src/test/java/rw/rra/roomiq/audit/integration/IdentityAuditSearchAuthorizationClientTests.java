package rw.rra.roomiq.audit.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import rw.rra.roomiq.common.web.CorrelationIdFilter;
import rw.rra.roomiq.common.web.DomainException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityAuditSearchAuthorizationClientTests {
    private static final String AUTHORIZATION = "Bearer signed-user-token";
    private static final UUID ACTOR_ID = UUID.randomUUID();

    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> forwardedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> forwardedCorrelationId = new AtomicReference<>();
    private HttpServer server;
    private volatile int responseStatus = 200;
    private volatile String responseBody = globalResponse(ACTOR_ID);
    private volatile long responseDelayMillis;
    private IdentityAuditSearchAuthorizationClient client;

    @BeforeEach
    void startIdentityStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/authorization/audit-search", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            forwardedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            forwardedCorrelationId.set(exchange.getRequestHeaders().getFirst(CorrelationIdFilter.HEADER_NAME));
            if (responseDelayMillis > 0) {
                try {
                    Thread.sleep(responseDelayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.start();
        client = new IdentityAuditSearchAuthorizationClient(RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofMillis(200), Duration.ofMillis(300));
    }

    @AfterEach
    void stopIdentityStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void forwardsCallerTokenAndCorrelationAndPreservesGlobalDecision() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "audit-correlation");
        AuditSearchAuthorizationDecision decision;
        try {
            decision = client.authorize(AUTHORIZATION, ACTOR_ID);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }

        assertThat(decision.accessScope()).isEqualTo(AuditSearchAuthorizationDecision.AccessScope.GLOBAL);
        assertThat(decision.buildingIds()).isEmpty();
        assertThat(requestPath.get()).isEqualTo("/api/v1/internal/authorization/audit-search");
        assertThat(forwardedAuthorization.get()).isEqualTo(AUTHORIZATION);
        assertThat(forwardedCorrelationId.get()).isEqualTo("audit-correlation");
    }

    @Test
    void preservesTrustedScopedBuildingIds() {
        UUID buildingId = UUID.randomUUID();
        responseBody = scopedResponse(ACTOR_ID, buildingId);

        AuditSearchAuthorizationDecision decision = client.authorize(AUTHORIZATION, ACTOR_ID);

        assertThat(decision.accessScope()).isEqualTo(AuditSearchAuthorizationDecision.AccessScope.BUILDING_SCOPED);
        assertThat(decision.buildingIds()).containsExactly(buildingId);
    }

    @Test
    void rejectsEmptyScopedResponseAndInconsistentIdentityOrScopeData() {
        responseBody = """
                {"actorUserId":"%s","accessScope":"BUILDING_SCOPED","buildingIds":[]}
                """.formatted(ACTOR_ID);
        assertThatThrownBy(() -> client.authorize(AUTHORIZATION, ACTOR_ID))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.SERVICE_UNAVAILABLE)
                .hasFieldOrPropertyWithValue("code", "IDENTITY_AUTHORIZATION_UNAVAILABLE");

        responseBody = globalResponse(UUID.randomUUID());
        assertUnavailable();

        responseBody = """
                {"actorUserId":"%s","accessScope":"GLOBAL","buildingIds":["%s"]}
                """.formatted(ACTOR_ID, UUID.randomUUID());
        assertUnavailable();

        UUID duplicatedBuildingId = UUID.randomUUID();
        responseBody = """
                {"actorUserId":"%s","accessScope":"BUILDING_SCOPED","buildingIds":["%s","%s"]}
                """.formatted(ACTOR_ID, duplicatedBuildingId, duplicatedBuildingId);
        assertUnavailable();
    }

    @Test
    void mapsIdentityAuthenticationAndAuthorizationDenialsWithoutFallback() {
        responseStatus = 401;
        responseBody = "{}";
        assertThatThrownBy(() -> client.authorize(AUTHORIZATION, ACTOR_ID))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);

        responseStatus = 403;
        assertThatThrownBy(() -> client.authorize(AUTHORIZATION, ACTOR_ID))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void mapsIdentityFailuresTimeoutAndInvalidBearerToFailClosedResponses() {
        responseStatus = 503;
        responseBody = "{}";
        assertUnavailable();

        responseStatus = 200;
        responseDelayMillis = 1000;
        assertUnavailable();

        assertThatThrownBy(() -> client.authorize("Bearer ", ACTOR_ID))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void mapsIdentityConnectionFailureToUnavailable() {
        server.stop(0);

        assertUnavailable();
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> client.authorize(AUTHORIZATION, ACTOR_ID))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.SERVICE_UNAVAILABLE)
                .hasMessage("Identity authorization is unavailable; the request cannot proceed");
    }

    private static String globalResponse(UUID actorId) {
        return """
                {"actorUserId":"%s","accessScope":"GLOBAL","buildingIds":[]}
                """.formatted(actorId);
    }

    private static String scopedResponse(UUID actorId, UUID buildingId) {
        return """
                {"actorUserId":"%s","accessScope":"BUILDING_SCOPED","buildingIds":["%s"]}
                """.formatted(actorId, buildingId);
    }
}
