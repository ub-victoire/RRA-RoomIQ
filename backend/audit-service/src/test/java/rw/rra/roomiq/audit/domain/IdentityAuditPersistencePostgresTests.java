package rw.rra.roomiq.audit.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "roomiq.audit.notification.events.enabled=false",
        "roomiq.audit.identity.events.enabled=false"
})
@Testcontainers
class IdentityAuditPersistencePostgresTests {
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
    private IdentityAuditEventCodec codec;

    @Autowired
    private IdentityAuditEventProcessor processor;

    @Autowired
    private AuditEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsTrustedIdentityFactsAndScopeThenDeduplicatesRedelivery() {
        UUID actorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID scopeId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var event = codec.decode(eventJson(eventId, "IDENTITY_USER_STATUS_CHANGED", "USER",
                "SUCCESS", actorId, resourceId, scopeId, "{}", "correlation-1"));

        processor.process(event);
        processor.process(event);

        AuditEvent saved = repository.findById(eventId).orElseThrow();
        assertThat(repository.findById(eventId)).isPresent();
        assertThat(saved.getActorUserId()).isEqualTo(actorId);
        assertThat(saved.getResourceId()).isEqualTo(resourceId);
        assertThat(saved.getScopeOfficeBuildingId()).isEqualTo(scopeId);
        assertThat(saved.getCorrelationId()).isEqualTo("correlation-1");
        assertThat(saved.getAction()).isEqualTo("IDENTITY_USER_STATUS_CHANGED");
        assertThat(saved.getMetadata()).contains("\"sourceService\": \"identity-service\"")
                .contains("\"schemaVersion\": 1");
    }

    @Test
    void persistsAuthenticationFailureWithoutFabricatingActorOrResource() {
        UUID eventId = UUID.randomUUID();
        var event = codec.decode(eventJson(eventId, "AUTHENTICATION_FAILED", "AUTHENTICATION",
                "DENIED", null, null, null, "{}", "correlation-unauthenticated"));

        processor.process(event);

        AuditEvent saved = repository.findById(eventId).orElseThrow();
        assertThat(saved.getActorUserId()).isNull();
        assertThat(saved.getResourceId()).isNull();
        assertThat(saved.getScopeOfficeBuildingId()).isNull();
        assertThat(saved.getOutcome()).isEqualTo("DENIED");
    }

    @Test
    void rejectsConflictingEventIdReuseIncludingScopeDifferences() {
        UUID eventId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        var original = codec.decode(eventJson(eventId, "IDENTITY_USER_STATUS_CHANGED", "USER",
                "SUCCESS", actorId, resourceId, UUID.randomUUID(), "{}", "correlation-1"));
        var conflicting = codec.decode(eventJson(eventId, "IDENTITY_USER_STATUS_CHANGED", "USER",
                "SUCCESS", actorId, resourceId, UUID.randomUUID(), "{}", "correlation-1"));

        processor.process(original);
        assertThatThrownBy(() -> processor.process(conflicting))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reused with different content");
        assertThat(repository.findById(eventId)).isPresent();
    }

    @Test
    void concurrentDuplicateDeliveriesPersistOneRecord() {
        UUID eventId = UUID.randomUUID();
        var event = codec.decode(eventJson(eventId, "IDENTITY_USER_STATUS_CHANGED", "USER",
                "SUCCESS", UUID.randomUUID(), UUID.randomUUID(), null, "{}", "correlation-concurrent"));
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = CompletableFuture.runAsync(() -> processAfter(start, event), executor);
            var second = CompletableFuture.runAsync(() -> processAfter(start, event), executor);
            start.countDown();
            CompletableFuture.allOf(first, second).join();
        }

        assertThat(repository.findById(eventId)).isPresent();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE id = ?", Integer.class, eventId)).isEqualTo(1);
    }

    @Test
    void runtimeRoleCannotMutateAuditRowsAndOwnerOperationsAreRejectedByTriggers() {
        UUID eventId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        processor.process(codec.decode(eventJson(eventId, "IDENTITY_USER_STATUS_CHANGED", "USER",
                "SUCCESS", actorId, resourceId, null, "{}", "correlation-immutable")));

        assertRejectedSqlState("42501", () -> jdbcTemplate.update(
                "UPDATE audit_event SET action = 'CHANGED' WHERE id = ?", eventId));
        assertRejectedSqlState("42501", () -> jdbcTemplate.update(
                "DELETE FROM audit_event WHERE id = ?", eventId));
        assertRejectedSqlState("42501", () -> jdbcTemplate.execute("TRUNCATE TABLE audit_event"));

        JdbcTemplate ownerJdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        assertRejectedSqlState("55000",
                () -> ownerJdbc.execute("UPDATE audit_event SET action = 'CHANGED' WHERE id = '" + eventId + "'"));
        assertRejectedSqlState("55000",
                () -> ownerJdbc.execute("DELETE FROM audit_event WHERE id = '" + eventId + "'"));
        assertRejectedSqlState("55000", () -> ownerJdbc.execute("TRUNCATE TABLE audit_event"));
        assertThat(repository.findById(eventId)).isPresent();
    }

    private static void assertRejectedSqlState(String sqlState, org.junit.jupiter.api.function.Executable operation) {
        assertThatThrownBy(operation::execute).satisfies(exception -> {
            Throwable root = exception;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(SQLException.class);
            assertThat(((SQLException) root).getSQLState()).isEqualTo(sqlState);
        });
    }

    private void processAfter(CountDownLatch start, IdentityAuditEvent event) {
        try {
            start.await();
            processor.process(event);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent audit test was interrupted", exception);
        }
    }

    private static byte[] eventJson(UUID eventId, String eventType, String resourceType, String outcome,
                                    UUID actorId, UUID resourceId, UUID scopeId, String metadata,
                                    String correlationId) {
        String actor = actorId == null ? "null" : "\"" + actorId + "\"";
        String resource = resourceId == null ? "null" : "\"" + resourceId + "\"";
        String scope = scopeId == null ? "null" : "\"" + scopeId + "\"";
        String payload = """
                {
                  "eventId":"%s",
                  "schemaVersion":1,
                  "eventType":"%s",
                  "timestamp":"2026-11-03T07:00:00Z",
                  "sourceService":"identity-service",
                  "actorUserId":%s,
                  "resourceType":"%s",
                  "resourceId":%s,
                  "scopeOfficeBuildingId":%s,
                  "outcome":"%s",
                  "correlationId":"%s",
                  "metadata":%s
                }
                """.formatted(eventId, eventType, actor, resourceType, resource, scope, outcome, correlationId,
                metadata);
        return payload.getBytes(StandardCharsets.UTF_8);
    }
}
