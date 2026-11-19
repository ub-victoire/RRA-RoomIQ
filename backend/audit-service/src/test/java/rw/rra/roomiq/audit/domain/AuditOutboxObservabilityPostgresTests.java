package rw.rra.roomiq.audit.domain;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import rw.rra.roomiq.audit.integration.AuditSourceEvidenceDeliveryClient;
import rw.rra.roomiq.audit.integration.AuditSourceEvidenceProperties;
import rw.rra.roomiq.audit.integration.AuditSourceEvidenceTokenProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "management.health.rabbit.enabled=false",
        "roomiq.auth.jwt-secret=test-only-signing-key-at-least-32-bytes-long",
        "roomiq.audit.notification.events.enabled=false",
        "roomiq.audit.identity.events.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class AuditOutboxObservabilityPostgresTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("audit-test-roles.sql");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "roomiq_audit_app");
        registry.add("spring.datasource.password", () -> "audit-app-test-password");
        registry.add("spring.flyway.user", () -> "roomiq_audit_migrator");
        registry.add("spring.flyway.password", () -> "audit-migrator-test-password");
    }

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditSourceEvidenceOutboxTransactions transactions;

    @Autowired
    private AuditSourceEvidenceOutboxRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AuditOutboxObservability observability;

    @BeforeEach
    void clearOutbox() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                "roomiq_audit_migrator", "audit-migrator-test-password");
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM audit_source_evidence_outbox");
        }
    }

    @Test
    void statusCountsAndOldestPendingAgeReflectPersistedOutboxRows() {
        insert("PENDING", "2 hours");
        insert("DELIVERED", "1 minute");
        insert("RECONCILIATION_REQUIRED", "1 minute");
        insert("CONFIGURATION_BLOCKED", "1 minute");

        assertThat(meters.find("roomiq.audit.outbox.records").tag("status", "PENDING").gauge()).isNotNull();
        assertThat(meters.get("roomiq.audit.outbox.records").tag("status", "PENDING").gauge().value())
                .isEqualTo(1);
        for (String status : Set.of("DELIVERED", "RECONCILIATION_REQUIRED", "BLOCKED")) {
            assertThat(meters.get("roomiq.audit.outbox.records").tag("status", status).gauge().value())
                    .isEqualTo(1);
        }
        assertThat(meters.get("roomiq.audit.outbox.oldest.pending.seconds").gauge().value())
                .isGreaterThanOrEqualTo(7200);
    }

    @Test
    void outboxMetersExposeOnlyConstantLowCardinalityLabels() {
        var outboxMeters = meters.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("roomiq.audit.outbox."))
                .toList();
        assertThat(outboxMeters).isNotEmpty();
        outboxMeters.forEach(meter -> meter.getId().getTags().forEach(tag -> {
            assertThat(tag.getKey()).isEqualTo("status");
            assertThat(tag.getValue()).isIn("PENDING", "DELIVERED", "RECONCILIATION_REQUIRED", "BLOCKED");
        }));
    }

    @Test
    void deliveryAttemptCounterIncrementsWhenDeliveryFailsBeforeHttp() {
        var counter = meters.find("roomiq.audit.outbox.delivery.attempts").counter();
        assertThat(counter).isNotNull();
        double before = counter.count();
        var tokenProvider = mock(AuditSourceEvidenceTokenProvider.class);
        when(tokenProvider.createAuthorizationHeader()).thenThrow(new IllegalStateException("synthetic"));
        var properties = new AuditSourceEvidenceProperties(false, "http://localhost:1",
                Duration.ofSeconds(1), Duration.ofSeconds(1), "");
        var client = new AuditSourceEvidenceDeliveryClient(properties, tokenProvider, observability);
        Instant now = Instant.now();
        var entry = new AuditSourceEvidenceOutboxEntry(UUID.randomUUID(), "SYNTHETIC", "TEST_CATEGORY",
                "OCCURRED_AT", now, now, now, null, 0, UUID.randomUUID());
        assertThatThrownBy(() -> client.deliver(entry)).isInstanceOf(IllegalStateException.class);
        assertThat(counter.count()).isEqualTo(before + 1);
    }

    @Test
    void committedRetryAndTerminalTransitionsIncrementTheirCounters() {
        var retries = meters.find("roomiq.audit.outbox.retries").counter();
        var terminal = meters.find("roomiq.audit.outbox.terminal").counter();
        assertThat(retries).isNotNull();
        assertThat(terminal).isNotNull();
        double beforeRetries = retries.count();
        double beforeTerminal = terminal.count();
        UUID eventId = insert("PENDING", "1 minute");
        UUID token = UUID.randomUUID();
        jdbc.update("""
                UPDATE audit_source_evidence_outbox SET claim_token = ?, claim_expires_at = ?
                WHERE audit_event_id = ?
                """, token, java.sql.Timestamp.from(Instant.now().plusSeconds(600)), eventId);
        assertThat(transactions.defer(eventId, token, Instant.now().plusSeconds(1), "TEST", false)).isTrue();
        assertThat(retries.count()).isEqualTo(beforeRetries + 1);
        assertThat(terminal.count()).isEqualTo(beforeTerminal);
        jdbc.update("""
                UPDATE audit_source_evidence_outbox SET claim_token = ?, claim_expires_at = ?
                WHERE audit_event_id = ?
                """, token, java.sql.Timestamp.from(Instant.now().plusSeconds(600)), eventId);
        assertThat(transactions.defer(eventId, token, Instant.now().plusSeconds(1), "TEST", true)).isTrue();
        assertThat(terminal.count()).isEqualTo(beforeTerminal + 1);
        assertThat(transactions.requireReconciliation(eventId, token, "TEST")).isFalse();
        assertThat(terminal.count()).isEqualTo(beforeTerminal + 1);
        UUID rejected = insert("PENDING", "1 minute");
        jdbc.update("""
                UPDATE audit_source_evidence_outbox SET claim_token = ?, claim_expires_at = ?
                WHERE audit_event_id = ?
                """, token, java.sql.Timestamp.from(Instant.now().plusSeconds(600)), rejected);
        assertThat(transactions.requireReconciliation(rejected, token, "TEST")).isTrue();
        assertThat(terminal.count()).isEqualTo(beforeTerminal + 2);
    }

    @Test
    void rolledBackAndStaleOutcomesDoNotIncrementCounters() {
        var retries = meters.find("roomiq.audit.outbox.retries").counter();
        assertThat(retries).isNotNull();
        double before = retries.count();
        UUID eventId = insert("PENDING", "1 minute");
        UUID token = UUID.randomUUID();
        jdbc.update("""
                UPDATE audit_source_evidence_outbox SET claim_token = ?, claim_expires_at = ?
                WHERE audit_event_id = ?
                """, token, java.sql.Timestamp.from(Instant.now().plusSeconds(600)), eventId);
        assertThat(transactions.defer(eventId, UUID.randomUUID(), Instant.now(), "TEST", false)).isFalse();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            assertThat(repository.defer(eventId, token, Instant.now(), "TEST", false)).isTrue();
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(retries.count()).isEqualTo(before);
    }

    @Test
    void expiredLeaseReclaimCounterReflectsCommittedRecovery() {
        var reclaims = meters.find("roomiq.audit.outbox.lease.reclaims").counter();
        var retries = meters.find("roomiq.audit.outbox.retries").counter();
        var terminal = meters.find("roomiq.audit.outbox.terminal").counter();
        assertThat(reclaims).isNotNull();
        double before = reclaims.count();
        double beforeRetries = retries.count();
        double beforeTerminal = terminal.count();
        UUID eventId = insert("PENDING", "1 minute");
        UUID exhaustedId = insert("PENDING", "1 minute");
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE audit_source_evidence_outbox SET claim_token = ?, claim_expires_at = ?
                WHERE audit_event_id = ?
                """, UUID.randomUUID(), java.sql.Timestamp.from(now.minusSeconds(1)), eventId);
        jdbc.update("""
                UPDATE audit_source_evidence_outbox SET claim_token = ?, claim_expires_at = ?, attempt_count = 9
                WHERE audit_event_id = ?
                """, UUID.randomUUID(), java.sql.Timestamp.from(now.minusSeconds(1)), exhaustedId);
        transactions.claimDue(now, now.plusSeconds(600), 1);
        assertThat(reclaims.count()).isEqualTo(before + 2);
        assertThat(retries.count()).isEqualTo(beforeRetries + 1);
        assertThat(terminal.count()).isEqualTo(beforeTerminal + 1);
    }

    @Test
    void rolledBackLeaseRecoveryDoesNotIncrementCounters() {
        var reclaims = meters.get("roomiq.audit.outbox.lease.reclaims").counter();
        var retries = meters.get("roomiq.audit.outbox.retries").counter();
        double beforeReclaims = reclaims.count();
        double beforeRetries = retries.count();
        UUID eventId = insert("PENDING", "1 minute");
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE audit_source_evidence_outbox SET claim_token = ?, claim_expires_at = ?
                WHERE audit_event_id = ?
                """, UUID.randomUUID(), java.sql.Timestamp.from(now.minusSeconds(1)), eventId);
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            repository.claimDue(now, now.plusSeconds(600), 1, UUID.randomUUID());
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(reclaims.count()).isEqualTo(beforeReclaims);
        assertThat(retries.count()).isEqualTo(beforeRetries);
    }

    @Test
    void metricsAndOutboxHealthDetailsRequireAuthentication() throws Exception {
        insert("PENDING", "2 hours");
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/health/outbox")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
        mockMvc.perform(get("/actuator/metrics/roomiq.audit.outbox.records")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTags[0].tag").value("status"));
        mockMvc.perform(get("/actuator/health/outbox").header("Authorization", "Bearer " + token()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.details.pending").isNumber())
                .andExpect(jsonPath("$.details.oldestPendingSeconds").isNumber());
    }

    @Test
    void publicHealthContainsNoBacklogCountsAgesOrThresholds() throws Exception {
        insert("PENDING", "2 hours");
        var response = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("pending", "Pending", "count", "Count", "age", "Age", "threshold");
        assertThat(response).doesNotContain("components", "details");
    }

    @Test
    void freshOrEmptyBacklogIsHealthy() throws Exception {
        mockMvc.perform(get("/actuator/health/outbox").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.pending").value(0))
                .andExpect(jsonPath("$.details.oldestPendingSeconds").value(0.0));
        insert("PENDING", "1 minute");
        mockMvc.perform(get("/actuator/health/outbox").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.pending").value(1));
    }

    @Test
    void sensitiveActuatorEndpointsRemainUnexposed() throws Exception {
        for (String endpoint : Set.of("env", "beans", "heapdump", "loggers", "info")) {
            var result = mockMvc.perform(get("/actuator/" + endpoint).header("Authorization", "Bearer " + token()))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
            assertThat(result.getResolvedException()).isInstanceOf(NoResourceFoundException.class);
        }
    }

    private UUID insert(String status, String age) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_source_evidence_outbox (
                    audit_event_id, action, category_code, start_basis_code, record_created_at,
                    occurred_at, observed_at, delivery_status, next_attempt_at, attempt_count,
                    delivered_at, created_at)
                VALUES (?, 'SYNTHETIC', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        ?, CURRENT_TIMESTAMP, 0, ?, CURRENT_TIMESTAMP - CAST(? AS INTERVAL))
                """, eventId, status.equals("CONFIGURATION_BLOCKED") ? null : "TEST_CATEGORY",
                status.equals("CONFIGURATION_BLOCKED") ? null : "OCCURRED_AT", status,
                status.equals("DELIVERED") ? java.sql.Timestamp.from(Instant.now()) : null, age);
        return eventId;
    }

    private static String token() {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "test-only-signing-key-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        Instant now = Instant.now();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject("synthetic-test-subject").issuedAt(now.minusSeconds(1))
                        .expiresAt(now.plusSeconds(60)).build())).getTokenValue();
    }
}
