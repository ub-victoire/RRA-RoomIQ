package rw.rra.roomiq.audit.domain;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import rw.rra.roomiq.audit.integration.AuditSourceEvidenceDeliveryClient;
import rw.rra.roomiq.audit.integration.AuditSourceEvidenceProperties;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "roomiq.auth.jwt-secret=test-only-signing-key-at-least-32-bytes-long",
        "roomiq.audit.notification.events.enabled=false",
        "roomiq.audit.identity.events.enabled=false"
})
@Testcontainers
class AuditSourceEvidenceOutboxRelayPostgresTests {
    private static final Instant DUE_AT = Instant.parse("2020-01-01T00:00:00Z");

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
    private AuditSourceEvidenceOutboxRelay relay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private AuditSourceEvidenceOutboxRepository outboxRepository;

    @MockitoBean
    private AuditSourceEvidenceDeliveryClient deliveryClient;

    @MockitoBean
    private TaskScheduler taskScheduler;

    @BeforeEach
    void postponePendingRowsFromPriorInvocations() {
        jdbcTemplate.update("""
                UPDATE audit_source_evidence_outbox
                SET next_attempt_at = ?
                WHERE category_code = 'TEST_AUDIT_CATEGORY' AND delivery_status = 'PENDING'
                """, Timestamp.from(Instant.parse("2099-01-01T00:00:00Z")));
    }

    @ParameterizedTest(name = "bad row first={0}")
    @ValueSource(booleans = {true, false})
    void uncheckedDeliveryFailureIsRetriedWithoutRollingBackOtherRows(boolean badRowFirst) {
        UUID failedEventId = UUID.randomUUID();
        UUID successfulEventId = UUID.randomUUID();
        UUID firstEventId = badRowFirst ? failedEventId : successfulEventId;
        UUID lastEventId = badRowFirst ? successfulEventId : failedEventId;
        insertPending(failedEventId, DUE_AT.plusSeconds(badRowFirst ? 0 : 1));
        insertPending(successfulEventId, DUE_AT.plusSeconds(badRowFirst ? 1 : 0));

        List<UUID> claimedBatchEventIds = new ArrayList<>();
        List<Integer> claimedBatchSizes = new ArrayList<>();
        List<UUID> deliveryOrder = new ArrayList<>();
        doAnswer(invocation -> {
            List<?> result = (List<?>) invocation.callRealMethod();
            List<AuditSourceEvidenceOutboxEntry> claimed = result.stream()
                    .map(AuditSourceEvidenceOutboxEntry.class::cast)
                    .toList();
            claimedBatchSizes.add(claimed.size());
            claimedBatchEventIds.addAll(claimed.stream()
                    .map(AuditSourceEvidenceOutboxEntry::auditEventId)
                    .toList());
            return result;
        }).when(outboxRepository).claimDue(any(Instant.class), any(Instant.class), eq(25), any(UUID.class));
        when(deliveryClient.deliver(any())).thenAnswer(invocation -> {
            AuditSourceEvidenceOutboxEntry entry = invocation.getArgument(0);
            deliveryOrder.add(entry.auditEventId());
            if (entry.auditEventId().equals(failedEventId)) {
                throw new RuntimeException("synthetic unchecked delivery failure");
            }
            return new AuditSourceEvidenceDeliveryClient.DeliveryAcknowledgement(
                    entry.auditEventId(), 1, "ACCEPTED");
        });

        Throwable failure = catchThrowable(relay::deliverPendingEvidence);
        if (failure != null) {
            failure.printStackTrace(System.err);
        }
        OutboxState failed = findState(failedEventId);
        OutboxState successful = findState(successfulEventId);

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(failure)
                    .as("an unchecked delivery failure must be isolated to its row")
                    .isNull();
            softly.assertThat(claimedBatchEventIds)
                    .as("the bad and healthy rows must be claimed together, in expected order")
                    .containsExactly(firstEventId, lastEventId);
            softly.assertThat(claimedBatchSizes)
                    .as("both rows must be returned by one claim operation")
                    .containsExactly(2);
            softly.assertThat(deliveryOrder)
                    .as("the relay must process the claimed batch in the asserted order")
                    .containsExactly(firstEventId, lastEventId);
            softly.assertThat(failed.deliveryStatus())
                    .as("a failed delivery below the retry cap must remain retryable")
                    .isEqualTo("PENDING");
            softly.assertThat(failed.attemptCount())
                    .as("a failed delivery must increment attempt_count")
                    .isEqualTo(1);
            softly.assertThat(failed.nextAttemptAt())
                    .as("a failed delivery must be scheduled for retry after its previous due time")
                    .isAfter(DUE_AT);
            softly.assertThat(successful.deliveryStatus())
                    .as("the healthy row must be delivered regardless of its position in the batch")
                    .isEqualTo("DELIVERED");
        });
    }

    private void insertPending(UUID eventId, Instant nextAttemptAt) {
        jdbcTemplate.update("""
                INSERT INTO audit_source_evidence_outbox (
                    audit_event_id, action, category_code, start_basis_code, record_created_at,
                    occurred_at, observed_at, delivery_status, next_attempt_at, attempt_count)
                VALUES (?, 'AUTHENTICATION_FAILED', 'TEST_AUDIT_CATEGORY', 'OCCURRED_AT',
                        ?, ?, ?, 'PENDING', ?, 0)
                """, eventId, Timestamp.from(DUE_AT), Timestamp.from(DUE_AT),
                Timestamp.from(DUE_AT), Timestamp.from(nextAttemptAt));
    }

    private OutboxState findState(UUID eventId) {
        return jdbcTemplate.queryForObject("""
                SELECT delivery_status, attempt_count, next_attempt_at
                FROM audit_source_evidence_outbox
                WHERE audit_event_id = ?
                """, (resultSet, rowNumber) -> new OutboxState(
                resultSet.getString("delivery_status"),
                resultSet.getInt("attempt_count"),
                resultSet.getTimestamp("next_attempt_at").toInstant()), eventId);
    }

    private record OutboxState(String deliveryStatus, int attemptCount, Instant nextAttemptAt) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RelayTestConfiguration {
        @Bean
        AuditSourceEvidenceOutboxRelay auditSourceEvidenceOutboxRelay(
                AuditSourceEvidenceOutboxRepository repository,
                AuditSourceEvidenceOutboxTransactions transactions,
                AuditSourceEvidenceDeliveryClient deliveryClient,
                AuditSourceEvidenceOutboxService outboxService) {
            var enabledProperties = new AuditSourceEvidenceProperties(
                    true,
                    "https://rlc.test",
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    "AUTHENTICATION_FAILED=TEST_AUDIT_CATEGORY:OCCURRED_AT");
            return new AuditSourceEvidenceOutboxRelay(
                    repository, transactions, enabledProperties, deliveryClient, outboxService);
        }
    }
}
