package rw.rra.roomiq.identity.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import rw.rra.roomiq.identity.domain.entity.AppUser;
import rw.rra.roomiq.identity.domain.entity.UserStatus;
import rw.rra.roomiq.identity.domain.repository.AppUserRepository;
import rw.rra.roomiq.identity.domain.repository.IdentityAuditOutboxRepository;
import rw.rra.roomiq.identity.domain.repository.UserSessionRepository;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:identity-auth-test",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "roomiq.auth.jwt-secret=test-only-signing-key-at-least-32-bytes-long",
        "roomiq.auth.access-token-ttl=PT15M",
        "roomiq.auth.refresh-token-ttl=P7D",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.flyway.clean-disabled=false")
class AuthenticationControllerTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

        @Autowired
        private IdentityAuditOutboxRepository auditOutboxRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void publicAndProtectedRoutesFollowTheExplicitSecurityAllowlist() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.path").value("/api/v1/users"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
        mockMvc.perform(get("/api/v1/auth/not-a-public-operation"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(post("/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/login"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"missing@rra.rw\",\"password\":\"Wrong-password-9\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
    }

    @Test
    void openApiDocumentsEveryIdentityOperationAndSecurityPolicy() throws Exception {
        String document = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> paths = JsonPath.read(document, "$.paths");
        assertThat(paths).hasSize(22);
        int operationCount = paths.values().stream()
                .mapToInt(path -> ((Map<?, ?>) path).size())
                .sum();
        assertThat(operationCount).isEqualTo(30);

        Map<?, ?> schemes = JsonPath.read(document, "$.components.securitySchemes");
        assertThat(schemes.keySet().toString()).contains("bearerAuth");
        Map<?, ?> bearerScheme = (Map<?, ?>) schemes.get("bearerAuth");
        assertThat(String.valueOf(bearerScheme.get("type"))).isEqualTo("http");
        assertThat(String.valueOf(bearerScheme.get("scheme"))).isEqualTo("bearer");
        assertThat(String.valueOf(bearerScheme.get("bearerFormat"))).isEqualTo("JWT");

        for (Map.Entry<?, ?> pathEntry : paths.entrySet()) {
            String path = pathEntry.getKey().toString();
            Map<?, ?> pathItem = (Map<?, ?>) pathEntry.getValue();
            for (Map.Entry<?, ?> methodEntry : pathItem.entrySet()) {
                Map<?, ?> operation = (Map<?, ?>) methodEntry.getValue();
                assertThat(String.valueOf(operation.get("summary")))
                        .as("summary for %s %s", methodEntry.getKey(), path)
                        .isNotBlank();
                boolean publicAuthenticationRoute = path.startsWith("/api/v1/auth/");
                assertThat(Boolean.valueOf(operation.containsKey("security")))
                        .as("security requirement for %s %s", methodEntry.getKey(), path)
                        .isEqualTo(!publicAuthenticationRoute);
                                if (!publicAuthenticationRoute) {
                                        assertThat(String.valueOf(operation.get("security"))).contains("bearerAuth");
                                }
                }
        }

        Object loginEmailSchema = JsonPath.read(document, "$.components.schemas.LoginRequest.properties.email");
        assertThat(loginEmailSchema.toString()).isNotBlank();
        List<?> userTags = JsonPath.read(document, "$.paths['/api/v1/users'].get.tags");
        assertThat(Boolean.valueOf(userTags.contains("Users"))).isTrue();
        Map<?, ?> roomAuthorization = JsonPath.read(document,
                "$.paths['/api/v1/internal/authorization/room-management'].post");
        assertThat(String.valueOf(roomAuthorization.get("summary"))).contains("room management");
        assertThat(String.valueOf(roomAuthorization.get("security"))).contains("bearerAuth");
        Map<?, ?> schedulingAuthorization = JsonPath.read(document,
                "$.paths['/api/v1/internal/authorization/scheduling'].post");
        assertThat(String.valueOf(schedulingAuthorization.get("summary"))).contains("scheduling");
        assertThat(String.valueOf(schedulingAuthorization.get("security"))).contains("bearerAuth");
        Map<?, ?> bookingAuthorization = JsonPath.read(document,
                "$.paths['/api/v1/internal/authorization/booking'].post");
        assertThat(String.valueOf(bookingAuthorization.get("summary"))).contains("Booking");
        assertThat(String.valueOf(bookingAuthorization.get("security"))).contains("bearerAuth");
        Map<?, ?> meetingAuthorization = JsonPath.read(document,
                "$.paths['/api/v1/internal/authorization/meeting'].post");
        assertThat(String.valueOf(meetingAuthorization.get("summary"))).contains("meeting");
        assertThat(String.valueOf(meetingAuthorization.get("security"))).contains("bearerAuth");
        Map<?, ?> templateAuthorization = JsonPath.read(document,
                "$.paths['/api/v1/internal/authorization/notification-templates'].post");
        assertThat(String.valueOf(templateAuthorization.get("summary"))).contains("notification-template");
        assertThat(String.valueOf(templateAuthorization.get("security"))).contains("bearerAuth");
    }

    @Test
    void loginRefreshRotationAndLogoutRevokeBearerAccess() throws Exception {
        AppUser user = createUser("auth-active@rra.rw", UserStatus.ACTIVE);
        String loginJson = """
                {"email":"auth-active@rra.rw","password":"Good-password-9"}
                """;
        String loginResponse = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .with(request -> { request.setRemoteAddr("192.0.2.10"); return request; })
                        .header("X-Correlation-ID", "identity-auth-login")
                        .header("User-Agent", "RRA-Test-Client/1.0")
                        .contentType(MediaType.APPLICATION_JSON).content(loginJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(loginResponse, "$.data.accessToken");
        String refreshToken = JsonPath.read(loginResponse, "$.data.refreshToken");
        String loginAudit = auditOutboxRepository
                .findTopByEventTypeOrderByCreatedAtDesc("AUTHENTICATION_SUCCEEDED").orElseThrow().getPayload();
        assertThat(loginAudit).contains("identity-auth-login", user.getId().toString(), "\"schemaVersion\":1")
                .doesNotContain("auth-active@rra.rw", "Good-password-9", accessToken, refreshToken);
        var decoded = jwtDecoder.decode(accessToken);
        assertThat(decoded.getSubject()).isEqualTo(user.getId().toString());
        var storedSessions = sessionRepository.findAllByUserIdAndRevokedAtIsNullAndExpiresAtAfter(
                user.getId(), java.time.Instant.now());
        assertThat(storedSessions).hasSize(1);
        assertThat(storedSessions.getFirst().getRefreshTokenHash()).isNotEqualTo(refreshToken);
        assertThat(storedSessions.getFirst().getRefreshTokenHash()).isEqualTo(sha256(refreshToken));
        assertThat(storedSessions.getFirst().getIpAddress()).isEqualTo("192.0.2.10");
        assertThat(storedSessions.getFirst().getUserAgent()).isEqualTo("RRA-Test-Client/1.0");
        assertThat(Duration.between(storedSessions.getFirst().getIssuedAt(), storedSessions.getFirst().getExpiresAt()))
                .isEqualTo(Duration.ofDays(7));

        mockMvc.perform(get("/api/v1/users/{id}", user.getId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        String refreshJson = "{\"refreshToken\":\"" + refreshToken + "\"}";
        String refreshedResponse = mockMvc.perform(post("/api/v1/auth/refresh").with(csrf())
                        .with(request -> { request.setRemoteAddr("192.0.2.11"); return request; })
                        .header("User-Agent", "RRA-Test-Client/2.0")
                        .contentType(MediaType.APPLICATION_JSON).content(refreshJson))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String rotatedRefresh = JsonPath.read(refreshedResponse, "$.data.refreshToken");
        String rotatedAccess = JsonPath.read(refreshedResponse, "$.data.accessToken");
        String refreshAudit = auditOutboxRepository
                .findTopByEventTypeOrderByCreatedAtDesc("REFRESH_TOKEN_ROTATED").orElseThrow().getPayload();
        assertThat(refreshAudit).contains(user.getId().toString())
                .doesNotContain("auth-active@rra.rw", refreshToken, rotatedRefresh, rotatedAccess);
        assertThat(rotatedRefresh).isNotEqualTo(refreshToken);
        assertThat(sessionRepository.findByRefreshTokenHash(sha256(refreshToken)).orElseThrow().getRevokedAt())
                .isNotNull();
        var rotatedSession = sessionRepository.findByRefreshTokenHash(sha256(rotatedRefresh)).orElseThrow();
        assertThat(rotatedSession.getIpAddress()).isEqualTo("192.0.2.11");
        assertThat(rotatedSession.getUserAgent()).isEqualTo("RRA-Test-Client/2.0");
        assertThat(rotatedSession.getExpiresAt()).isEqualTo(rotatedSession.getIssuedAt().plus(Duration.ofDays(7)));

        mockMvc.perform(post("/api/v1/auth/refresh").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(refreshJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        assertThat(auditOutboxRepository.findTopByEventTypeOrderByCreatedAtDesc("REFRESH_TOKEN_REJECTED"))
                .isPresent();

        String logoutJson = "{\"refreshToken\":\"" + rotatedRefresh + "\"}";
        mockMvc.perform(post("/api/v1/auth/logout").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(logoutJson))
                .andExpect(status().isOk());
        String logoutAudit = auditOutboxRepository
                .findTopByEventTypeOrderByCreatedAtDesc("AUTHENTICATION_LOGOUT").orElseThrow().getPayload();
        assertThat(logoutAudit).contains(user.getId().toString())
                .doesNotContain(rotatedRefresh, rotatedAccess, "auth-active@rra.rw");
        assertThat(sessionRepository.findByRefreshTokenHash(sha256(rotatedRefresh)).orElseThrow().getRevokedAt())
                .isNotNull();
        mockMvc.perform(get("/api/v1/users/{id}", user.getId())
                        .header("Authorization", "Bearer " + rotatedAccess))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.path").value("/api/v1/users/" + user.getId()))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void expiredRefreshSessionIsPersistentlyRevoked() throws Exception {
        AppUser user = createUser("auth-expired-session@rra.rw", UserStatus.ACTIVE);
        String rawRefreshToken = "expired-session-refresh-token";
        Instant issuedAt = Instant.now().minus(Duration.ofDays(8));
        var expired = sessionRepository.saveAndFlush(new rw.rra.roomiq.identity.domain.entity.UserSession(
                user, sha256(rawRefreshToken), issuedAt, issuedAt.plus(Duration.ofDays(7))));

        mockMvc.perform(post("/api/v1/auth/refresh").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rawRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(sessionRepository.findById(expired.getId()).orElseThrow().getRevokedAt()).isNotNull();
    }

    @Test
    void invalidPasswordAndInactiveAccountsAreRejectedUniformly() throws Exception {
        createUser("auth-pending@rra.rw", UserStatus.PENDING);
        createUser("auth-suspended@rra.rw", UserStatus.SUSPENDED);
        createUser("auth-disabled@rra.rw", UserStatus.DISABLED);
        createUser("auth-invalid@rra.rw", UserStatus.ACTIVE);

        assertLoginRejected("auth-pending@rra.rw", "Good-password-9");
        assertLoginRejected("auth-suspended@rra.rw", "Good-password-9");
        assertLoginRejected("auth-disabled@rra.rw", "Good-password-9");
        assertLoginRejected("auth-invalid@rra.rw", "Wrong-password-9");
        assertLoginRejected("missing@rra.rw", "Wrong-password-9");
    }

    @Test
    void logoutIsIdempotentForUnknownRefreshToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"unknown-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private AppUser createUser(String email, UserStatus status) {
        AppUser user = new AppUser(email, "Authentication Test", status);
        user.replacePasswordHash(passwordEncoder.encode("Good-password-9"));
        return userRepository.saveAndFlush(user);
    }

    private void assertLoginRejected(String email, String password) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
        mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Email or password was not accepted"));
        String payload = auditOutboxRepository
                .findTopByEventTypeOrderByCreatedAtDesc("AUTHENTICATION_FAILED").orElseThrow().getPayload();
        assertThat(payload).doesNotContain(email, password);
    }

    private String sha256(String rawToken) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }
}
