package rw.rra.roomiq.lifecycle.retention;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class EmptyRetentionSchedulePostgresTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("rlc-test-roles.sql");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("RLC_DB_URL", postgres::getJdbcUrl);
        registry.add("RLC_DB_USERNAME", () -> "roomiq_rlc_app");
        registry.add("RLC_DB_PASSWORD", () -> "rlc-app-test-password");
        registry.add("RLC_DB_MIGRATION_USERNAME", () -> "roomiq_rlc_migrator");
        registry.add("RLC_DB_MIGRATION_PASSWORD", () -> "rlc-migrator-test-password");
        registry.add("roomiq.auth.jwt-secret", () -> "test-only-signing-key-at-least-32-bytes-long");
        registry.add("roomiq.identity.url", () -> "http://localhost:8081");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RetentionPolicyEvaluationService service;

    @Test
    void migratedDefaultScheduleIsEmptyAndNoRecordsAreEligible() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifecycle_policy_version", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lifecycle_policy_rule", Long.class)).isZero();
        Instant start = Instant.parse("2020-01-01T00:00:00Z");
        Instant evaluationAt = Instant.parse("2040-01-01T00:00:00Z");
        List<RetentionEvaluationEvidence> records = List.of(
                new RetentionEvaluationEvidence(true, true, "TEST_A", start, evaluationAt,
                        Map.of("ORIGINAL_EVENT", start), Map.of(), true, List.of()),
                new RetentionEvaluationEvidence(true, true, "TEST_B", start, evaluationAt,
                        Map.of("ORIGINAL_EVENT", start), Map.of(), true, List.of()));

        assertThat(records.stream().map(service::evaluate))
                .allSatisfy(result -> {
                    assertThat(result.eligible()).isFalse();
                    assertThat(result.reason()).isEqualTo(RetentionEvaluationResult.Reason.NO_APPLICABLE_POLICY);
                });
    }
}
