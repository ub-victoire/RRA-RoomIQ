package rw.rra.roomiq.audit.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.rra.roomiq.audit.integration.AuditSearchAuthorizationDecision;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:audit-security-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "roomiq.auth.jwt-secret=test-only-signing-key-at-least-32-bytes-long",
        "roomiq.identity.connect-timeout=100ms",
        "roomiq.identity.read-timeout=150ms",
        "roomiq.audit.notification.events.enabled=false",
        "roomiq.audit.identity.events.enabled=false"
})
@AutoConfigureMockMvc
@Import(AuditSecurityIntegrationTests.ProbeConfiguration.class)
class AuditSecurityIntegrationTests {
    private static final String ACTOR_ID = UUID.randomUUID().toString();
    private static final AtomicReference<Integer> identityStatus = new AtomicReference<>(200);
    private static final AtomicReference<String> identityBody = new AtomicReference<>();
    private static final AtomicReference<Long> identityDelayMillis = new AtomicReference<>(0L);
    private static HttpServer identityServer;

    @Autowired
    private MockMvc mockMvc;

    private final JwtEncoder testJwtEncoder = new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
            new ImmutableSecret<>(new SecretKeySpec(
                    "test-only-signing-key-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256")));

    @DynamicPropertySource
    static void configureIdentity(DynamicPropertyRegistry registry) {
        registry.add("roomiq.identity.url", AuditSecurityIntegrationTests::identityUrl);
    }

    @BeforeEach
    void resetIdentityStub() {
        identityStatus.set(200);
        identityBody.set(globalResponse(ACTOR_ID));
        identityDelayMillis.set(0L);
    }

    @AfterAll
    static void stopIdentityStub() {
        if (identityServer != null) {
            identityServer.stop(0);
        }
    }

    @Test
    void rejectsMissingInvalidAndExpiredBearerTokens() throws Exception {
        mockMvc.perform(get("/api/test/audit-auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(get("/api/test/audit-auth").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        String expiredToken = testJwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .subject(ACTOR_ID)
                        .issuedAt(Instant.now().minusSeconds(600))
                        .expiresAt(Instant.now().minusSeconds(300))
                        .build())).getTokenValue();
        mockMvc.perform(get("/api/test/audit-auth").header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void validAuthenticatedRequestUsesTheIdentityGlobalDecision() throws Exception {
        mockMvc.perform(get("/api/test/audit-auth")
                        .header("Authorization", "Bearer " + validToken(ACTOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorUserId").value(ACTOR_ID))
                .andExpect(jsonPath("$.accessScope").value("GLOBAL"))
                .andExpect(jsonPath("$.buildingIds").isEmpty());
    }

    @Test
    void validAuthenticatedRequestPreservesScopedIdentityDecision() throws Exception {
        String buildingId = UUID.randomUUID().toString();
        identityBody.set(scopedResponse(ACTOR_ID, buildingId));

        mockMvc.perform(get("/api/test/audit-auth")
                        .header("Authorization", "Bearer " + validToken(ACTOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessScope").value("BUILDING_SCOPED"))
                .andExpect(jsonPath("$.buildingIds[0]").value(buildingId));
    }

    @Test
    void emptyScopesMalformedResponsesAndIdentityDenialsFailClosed() throws Exception {
        identityBody.set("""
                {"actorUserId":"%s","accessScope":"BUILDING_SCOPED","buildingIds":[]}
                """.formatted(ACTOR_ID));
        mockMvc.perform(authenticatedRequest())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_AUTHORIZATION_UNAVAILABLE"));

        identityBody.set("{\"actorUserId\":\"not-a-uuid\",\"accessScope\":\"GLOBAL\",\"buildingIds\":[]}");
        mockMvc.perform(authenticatedRequest())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_AUTHORIZATION_UNAVAILABLE"));

        identityStatus.set(401);
        mockMvc.perform(authenticatedRequest())
                .andExpect(status().isUnauthorized());

        identityStatus.set(403);
        mockMvc.perform(authenticatedRequest())
                .andExpect(status().isForbidden());
    }

    @Test
    void identityTimeoutAndServerFailureReturnUnavailableWithoutGlobalFallback() throws Exception {
        identityStatus.set(503);
        mockMvc.perform(authenticatedRequest())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_AUTHORIZATION_UNAVAILABLE"));

        identityStatus.set(200);
        identityDelayMillis.set(1000L);
        mockMvc.perform(authenticatedRequest())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_AUTHORIZATION_UNAVAILABLE"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticatedRequest() {
        return get("/api/test/audit-auth")
                .header("Authorization", "Bearer " + validToken(ACTOR_ID));
    }

    private String validToken(String subject) {
        Instant now = Instant.now();
        return testJwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject(subject).issuedAt(now.minusSeconds(1))
                        .expiresAt(now.plusSeconds(60)).build())).getTokenValue();
    }

    private static String identityUrl() {
        if (identityServer == null) {
            try {
                identityServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                identityServer.createContext("/api/v1/internal/authorization/audit-search", exchange -> {
                    long delay = identityDelayMillis.get();
                    if (delay > 0) {
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    byte[] body = identityBody.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                    exchange.sendResponseHeaders(identityStatus.get(), body.length);
                    try (var response = exchange.getResponseBody()) {
                        response.write(body);
                    }
                });
                identityServer.start();
            } catch (IOException exception) {
                throw new IllegalStateException("Could not start Identity authorization test server", exception);
            }
        }
        return "http://127.0.0.1:" + identityServer.getAddress().getPort();
    }

    private static String globalResponse(String actorId) {
        return """
                {"actorUserId":"%s","accessScope":"GLOBAL","buildingIds":[]}
                """.formatted(actorId);
    }

    private static String scopedResponse(String actorId, String buildingId) {
        return """
                {"actorUserId":"%s","accessScope":"BUILDING_SCOPED","buildingIds":["%s"]}
                """.formatted(actorId, buildingId);
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/test/audit-auth")
        AuditSearchAuthorizationDecision authorizationDecision(
                jakarta.servlet.http.HttpServletRequest request) {
            return (AuditSearchAuthorizationDecision) request.getAttribute(
                    AuditIdentityAuthorizationFilter.AUTHORIZATION_ATTRIBUTE);
        }
    }
}
