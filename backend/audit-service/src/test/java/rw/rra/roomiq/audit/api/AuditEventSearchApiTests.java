package rw.rra.roomiq.audit.api;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rw.rra.roomiq.audit.domain.AuditEvent;
import rw.rra.roomiq.audit.domain.AuditEventRepository;
import rw.rra.roomiq.audit.domain.AuditEventSearchCriteria;
import rw.rra.roomiq.audit.domain.AuditEventSearchService;
import rw.rra.roomiq.audit.integration.AuditSearchAuthorizationDecision;
import rw.rra.roomiq.common.web.GlobalExceptionHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:audit-search-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "roomiq.auth.jwt-secret=test-only-signing-key-at-least-32-bytes-long",
        "roomiq.identity.connect-timeout=1s",
        "roomiq.identity.read-timeout=2s",
        "roomiq.audit.notification.events.enabled=false",
        "roomiq.audit.identity.events.enabled=false"
})
@AutoConfigureMockMvc
class AuditEventSearchApiTests {
    private static final UUID ACTOR_ID =
            UUID.fromString("4af0f852-c658-4fdc-bc99-a6f501e6c6d9");
    private static final UUID EVENT_ID =
            UUID.fromString("1e4a415f-a2f0-49c7-a23d-823ab3aef1d4");
    private static final UUID BUILDING_ID =
            UUID.fromString("b74ad68a-300f-4f4d-8a8b-3ba9a4f5d38d");
    private static final UUID RESOURCE_ID =
            UUID.fromString("fd344792-fd6e-4dca-bc7a-947a0c282d92");
    private static final AtomicReference<Integer> identityStatus = new AtomicReference<>(200);
    private static final AtomicReference<String> identityBody =
            new AtomicReference<>(globalResponse(ACTOR_ID));
    private static final AtomicReference<Long> identityDelayMillis = new AtomicReference<>(0L);
    private static final AtomicReference<String> forwardedCorrelationId = new AtomicReference<>();
    private static final AtomicReference<String> forwardedAuthorization = new AtomicReference<>();
    private static final AtomicReference<List<AuditEvent>> resultContent = new AtomicReference<>(List.of());
    private static final AtomicReference<Long> resultTotal = new AtomicReference<>(0L);
    private static HttpServer identityServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuditEventRepository repository;

    @MockitoSpyBean
    private AuditEventSearchService searchService;

    @DynamicPropertySource
    static void configureIdentity(DynamicPropertyRegistry registry) {
        registry.add("roomiq.identity.url", AuditEventSearchApiTests::identityUrl);
    }

    @BeforeEach
    void resetIdentityStubAndRepository() {
        identityStatus.set(200);
        identityBody.set(globalResponse(ACTOR_ID));
        identityDelayMillis.set(0L);
        forwardedCorrelationId.set(null);
        forwardedAuthorization.set(null);
        resultContent.set(List.of(sampleEvent()));
        resultTotal.set(50L);
        reset(repository);
        when(repository.search(any(), any())).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            return new PageImpl<>(resultContent.get(), pageable, resultTotal.get());
        });
    }

    @AfterAll
    static void stopIdentityStub() {
        if (identityServer != null) {
            identityServer.stop(0);
        }
    }

    @Test
    void globalSearchBindsAllFiltersUsesTrustedDecisionAndReturnsOnlyPublicFields() throws Exception {
        String correlationId = "audit-search-api-001";
        String authorization = bearer();
        mockMvc.perform(get("/api/v1/audit-events")
                        .header("Authorization", authorization)
                        .header("X-Correlation-ID", correlationId)
                        .queryParam("action", "IDENTITY_USER_STATUS_CHANGED")
                        .queryParam("resourceType", "USER")
                        .queryParam("resourceId", RESOURCE_ID.toString())
                        .queryParam("actorUserId", RESOURCE_ID.toString())
                        .queryParam("outcome", "SUCCESS")
                        .queryParam("correlationId", "event-correlation")
                        .queryParam("buildingId", BUILDING_ID.toString())
                        .queryParam("occurredFrom", "2026-01-01T00:00:00Z")
                        .queryParam("occurredTo", "2026-02-01T02:00:00+02:00")
                        .queryParam("page", "2")
                        .queryParam("size", "10")
                        .queryParam("sortBy", "buildingId")
                        .queryParam("sortDirection", "ASC"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", correlationId))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(50))
                .andExpect(jsonPath("$.data.sortBy").value("buildingId"))
                .andExpect(jsonPath("$.data.sortDirection").value("ASC"))
                .andExpect(jsonPath("$.data.content[0].id").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.data.content[0].buildingId").value(BUILDING_ID.toString()))
                .andExpect(jsonPath("$.data.content[0].metadata").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].sourceService").doesNotExist());

        assertThat(forwardedAuthorization.get()).isEqualTo(authorization);
        assertThat(forwardedCorrelationId.get()).isEqualTo(correlationId);
        var criteriaCaptor = org.mockito.ArgumentCaptor.forClass(AuditEventSearchCriteria.class);
        var decisionCaptor = org.mockito.ArgumentCaptor.forClass(AuditSearchAuthorizationDecision.class);
        verify(searchService).search(criteriaCaptor.capture(), decisionCaptor.capture());
        assertThat(criteriaCaptor.getValue()).isEqualTo(new AuditEventSearchCriteria(
                "IDENTITY_USER_STATUS_CHANGED", "USER", RESOURCE_ID, RESOURCE_ID, "SUCCESS",
                "event-correlation", BUILDING_ID, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"), 2, 10, "buildingId", "ASC"));
        assertThat(decisionCaptor.getValue()).isEqualTo(new AuditSearchAuthorizationDecision(
                ACTOR_ID, AuditSearchAuthorizationDecision.AccessScope.GLOBAL, List.of()));
        verify(repository).search(any(), any());

        MvcResult result = mockMvc.perform(get("/api/v1/audit-events")
                        .header("Authorization", bearer()))
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("data");
        List<String> fields = data.get("content").get(0).properties().stream()
                .map(java.util.Map.Entry::getKey).toList();
        assertThat(fields).containsExactlyInAnyOrder("id", "actorUserId", "action", "resourceType", "resourceId",
                "buildingId", "outcome", "correlationId", "occurredAt");
    }

    @Test
    void scopedSearchUsesIdentityDecisionAndDefaultPageAndSortValues() throws Exception {
        identityBody.set(scopedResponse(ACTOR_ID, BUILDING_ID));
        mockMvc.perform(get("/api/v1/audit-events")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.sortBy").value("occurredAt"))
                .andExpect(jsonPath("$.data.sortDirection").value("DESC"));

        var decisionCaptor = org.mockito.ArgumentCaptor.forClass(AuditSearchAuthorizationDecision.class);
        verify(searchService).search(any(), decisionCaptor.capture());
        assertThat(decisionCaptor.getValue()).isEqualTo(new AuditSearchAuthorizationDecision(
                ACTOR_ID, AuditSearchAuthorizationDecision.AccessScope.BUILDING_SCOPED, List.of(BUILDING_ID)));
    }

    @Test
    void correlationIdIsGeneratedOrValidatedAndConsistentlyForwarded() throws Exception {
        MvcResult omittedHeader = mockMvc.perform(get("/api/v1/audit-events")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andReturn();
        String generatedId = omittedHeader.getResponse().getHeader("X-Correlation-ID");
        assertThat(generatedId).matches("[A-Za-z0-9._-]{1,128}");
        assertThat(forwardedCorrelationId.get()).isEqualTo(generatedId);

        forwardedCorrelationId.set(null);
        MvcResult invalidHeader = mockMvc.perform(get("/api/v1/audit-events")
                        .header("Authorization", bearer())
                        .header("X-Correlation-ID", "invalid correlation id"))
                .andExpect(status().isOk())
                .andReturn();
        String replacementId = invalidHeader.getResponse().getHeader("X-Correlation-ID");
        assertThat(replacementId).matches("[A-Za-z0-9._-]{1,128}")
                .isNotEqualTo("invalid correlation id");
        assertThat(forwardedCorrelationId.get()).isEqualTo(replacementId);

        forwardedCorrelationId.set(null);
        String suppliedId = "valid_correlation-123";
        MvcResult validHeader = mockMvc.perform(get("/api/v1/audit-events")
                        .header("Authorization", bearer())
                        .header("X-Correlation-ID", suppliedId))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(validHeader.getResponse().getHeader("X-Correlation-ID")).isEqualTo(suppliedId);
        assertThat(forwardedCorrelationId.get()).isEqualTo(suppliedId);
    }

    @Test
    void missingTrustedAuthorizationAttributeReturnsUnavailableWithoutSearching() throws Exception {
        AuditEventSearchService isolatedSearchService = mock(AuditEventSearchService.class);
        MockMvc isolatedMvc = MockMvcBuilders.standaloneSetup(
                        new AuditEventSearchController(isolatedSearchService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        isolatedMvc.perform(get("/api/v1/audit-events"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_AUTHORIZATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value(
                        "Identity authorization is unavailable; the request cannot proceed"));

        verifyNoInteractions(isolatedSearchService, repository);
    }

    @Test
    void identityErrorDetailsAndBearerTokenAreNotReturnedToCaller() throws Exception {
        String authorization = bearer();
        String upstreamDetails = """
                {"message":"internal exception detail","sql":"SELECT private_data FROM audit_event",
                 "stackTrace":"java.lang.IllegalStateException at private.Handler.run",
                 "authorization":"%s","detail":"raw-upstream-response-marker"}
                """.formatted(authorization);
        identityStatus.set(500);
        identityBody.set(upstreamDetails);

        MvcResult result = mockMvc.perform(get("/api/v1/audit-events")
                        .header("Authorization", authorization))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_AUTHORIZATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        "Identity authorization is unavailable; the request cannot proceed"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody)
                .doesNotContain("internal exception detail", "SELECT private_data", "stackTrace",
                        "IllegalStateException", "raw-upstream-response-marker", authorization, "Bearer ");
        verifyNoInteractions(repository);
    }

    @Test
    void nullablePublicFieldsAreSerializedAsNull() throws Exception {
        resultContent.set(List.of(new AuditEvent(UUID.randomUUID(), null, "NULLABLE_FIELDS_TEST", "RESOURCE",
                null, null, "SUCCESS", null, "{\"private\":\"metadata\"}",
                Instant.parse("2026-03-01T10:00:00Z"))));
        resultTotal.set(1L);

        MvcResult result = mockMvc.perform(get("/api/v1/audit-events")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode item = objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("data").path("content").get(0);
        assertThat(item.has("actorUserId")).isTrue();
        assertThat(item.get("actorUserId").isNull()).isTrue();
        assertThat(item.has("resourceId")).isTrue();
        assertThat(item.get("resourceId").isNull()).isTrue();
        assertThat(item.has("buildingId")).isTrue();
        assertThat(item.get("buildingId").isNull()).isTrue();
        assertThat(item.has("correlationId")).isTrue();
        assertThat(item.get("correlationId").isNull()).isTrue();
        assertThat(item.has("metadata")).isFalse();
    }

    @Test
    void emptyAndBeyondLastPageResultsRetainPageMetadata() throws Exception {
        resultContent.set(List.of());
        resultTotal.set(0L);
        mockMvc.perform(get("/api/v1/audit-events")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));

        resultTotal.set(1L);
        mockMvc.perform(get("/api/v1/audit-events")
                        .header("Authorization", bearer())
                        .queryParam("page", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.page").value(8))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void malformedFiltersAndInvalidRangesReturnSharedBadRequestEnvelope() throws Exception {
        List<MockHttpServletRequestBuilder> invalidRequests = List.of(
                get("/api/v1/audit-events").queryParam("resourceId", "not-a-uuid"),
                get("/api/v1/audit-events").queryParam("actorUserId", "not-a-uuid"),
                get("/api/v1/audit-events").queryParam("buildingId", "not-a-uuid"),
                get("/api/v1/audit-events").queryParam("occurredFrom", "not-a-date"),
                get("/api/v1/audit-events").queryParam("occurredFrom", "2026-01-01T00:00:00"),
                get("/api/v1/audit-events").queryParam("occurredTo", "2026-01-01T00:00:00"),
                get("/api/v1/audit-events").queryParam("occurredFrom", "2026-02-01T00:00:00Z")
                        .queryParam("occurredTo", "2026-01-01T00:00:00Z"),
                get("/api/v1/audit-events").queryParam("occurredFrom", "2026-01-01T00:00:00Z")
                        .queryParam("occurredTo", "2026-01-01T00:00:00Z"),
                get("/api/v1/audit-events").queryParam("page", "-1"),
                get("/api/v1/audit-events").queryParam("page", "not-an-int"),
                get("/api/v1/audit-events").queryParam("page", ""),
                get("/api/v1/audit-events").queryParam("size", "0"),
                get("/api/v1/audit-events").queryParam("size", "101"),
                get("/api/v1/audit-events").queryParam("size", "bad"),
                get("/api/v1/audit-events").queryParam("size", ""),
                get("/api/v1/audit-events").queryParam("sortBy", "metadata"),
                get("/api/v1/audit-events").queryParam("sortDirection", "SIDEWAYS"),
                get("/api/v1/audit-events").queryParam("page", "21474837").queryParam("size", "100"),
                get("/api/v1/audit-events").queryParam("page", "2147483648"));

        for (MockHttpServletRequestBuilder invalidRequest : invalidRequests) {
            invalidRequest.header("Authorization", bearer());
            mockMvc.perform(invalidRequest)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.is("INVALID_REQUEST"),
                            org.hamcrest.Matchers.is("INVALID_SORT_FIELD"))))
                    .andExpect(jsonPath("$.status").value(400));
        }

        verifyNoInteractions(repository);
    }

    @Test
    void buildingOutsideIdentityScopeIsDeniedBeforeRepositorySearch() throws Exception {
        identityBody.set(scopedResponse(ACTOR_ID, BUILDING_ID));
        mockMvc.perform(get("/api/v1/audit-events")
                        .header("Authorization", bearer())
                        .queryParam("buildingId", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(repository);
    }

    @Test
    void authenticationAndIdentityAuthorizationFailuresUseApprovedStatuses() throws Exception {
        mockMvc.perform(get("/api/v1/audit-events"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/audit-events").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        identityStatus.set(401);
        mockMvc.perform(get("/api/v1/audit-events").header("Authorization", bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        identityStatus.set(403);
        mockMvc.perform(get("/api/v1/audit-events").header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        identityStatus.set(200);
        identityBody.set("""
                {"actorUserId":"%s","accessScope":"BUILDING_SCOPED","buildingIds":[]}
                """.formatted(ACTOR_ID));
        mockMvc.perform(get("/api/v1/audit-events").header("Authorization", bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_AUTHORIZATION_UNAVAILABLE"));

        identityStatus.set(503);
        mockMvc.perform(get("/api/v1/audit-events").header("Authorization", bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_AUTHORIZATION_UNAVAILABLE"));

        identityStatus.set(200);
        identityBody.set(globalResponse(ACTOR_ID));
        identityDelayMillis.set(3_000L);
        mockMvc.perform(get("/api/v1/audit-events").header("Authorization", bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_AUTHORIZATION_UNAVAILABLE"));

        verifyNoInteractions(repository);
    }

    @Test
    void openApiDocumentsSearchParametersSecurityAndErrorResponses() throws Exception {
        mockMvc.perform(get("/v3/api-docs").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/audit-events'].get.parameters").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/audit-events'].get.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/AuditEventSearchApiResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/audit-events'].get.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/audit-events'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/audit-events'].get.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/audit-events'].get.responses['503']").exists())
                .andExpect(jsonPath("$.components.schemas.AuditEventSearchPage.properties.totalPages").exists())
                .andExpect(jsonPath("$.components.schemas.AuditEventSearchItem.properties.buildingId").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    private static AuditEvent sampleEvent() {
        return new AuditEvent(EVENT_ID, ACTOR_ID, "IDENTITY_USER_STATUS_CHANGED", "USER", RESOURCE_ID,
                BUILDING_ID, "SUCCESS", "event-correlation", "{\"private\":\"do-not-return\"}",
                Instant.parse("2026-01-15T12:00:00Z"));
    }

    private static String bearer() {
        return "Bearer " + new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec("test-only-signing-key-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256"))).encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .subject(ACTOR_ID.toString())
                        .issuedAt(Instant.now().minusSeconds(1))
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build())).getTokenValue();
    }

    private static String identityUrl() {
        try {
            identityServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            identityServer.createContext("/api/v1/internal/authorization/audit-search", exchange -> {
                forwardedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                forwardedCorrelationId.set(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
                if (identityDelayMillis.get() > 0) {
                    try {
                        Thread.sleep(identityDelayMillis.get());
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                byte[] body = identityBody.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(identityStatus.get(), body.length);
                try (var response = exchange.getResponseBody()) {
                    response.write(body);
                } catch (IOException ignored) {
                    // The client may time out before the stub finishes writing.
                }
            });
            identityServer.start();
            return "http://127.0.0.1:" + identityServer.getAddress().getPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start Identity authorization test server", exception);
        }
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
