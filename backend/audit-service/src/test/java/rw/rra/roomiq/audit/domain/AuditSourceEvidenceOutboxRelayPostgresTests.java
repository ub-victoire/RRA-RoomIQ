package rw.rra.roomiq.audit.domain;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    private static final Instant CLAIMED_AT = Instant.parse("2025-01-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(10);

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

    @Autowired
    private AuditSourceEvidenceOutboxTransactions transactions;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
                SET next_attempt_at = ?, claim_token = NULL, claim_expires_at = NULL
                WHERE category_code = 'TEST_AUDIT_CATEGORY' AND delivery_status = 'PENDING'
                """, Timestamp.from(Instant.parse("2099-01-01T00:00:00Z")));
    }

    @Test
    void concurrentWorkersCannotClaimTheSameRow() throws Exception {
        UUID eventId = UUID.randomUUID();
        insertPending(eventId, DUE_AT);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        UUID firstToken = UUID.randomUUID();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                var rows = outboxRepository.claimDue(CLAIMED_AT, CLAIMED_AT.plus(LEASE), 1, firstToken);
                claimed.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release the first claim");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while holding the first claim", exception);
                }
                return rows;
            }));
            try {
                assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() ->
                        transactions.claimDue(CLAIMED_AT, CLAIMED_AT.plus(LEASE), 1));
                assertThat(second.get(10, TimeUnit.SECONDS)).isEmpty();
            } finally {
                release.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS))
                    .extracting(AuditSourceEvidenceOutboxEntry::auditEventId)
                    .containsExactly(eventId);
        }
        assertThat(transactions.claimDue(CLAIMED_AT, CLAIMED_AT.plus(LEASE), 1)).isEmpty();
    }

    @Test
    void expiredLeaseIsReclaimedAfterAccountingAndBackoff() {
        UUID eventId = UUID.randomUUID();
        insertPending(eventId, DUE_AT);
        var firstClaim = transactions.claimDue(CLAIMED_AT, CLAIMED_AT.plus(LEASE), 1).getFirst();
        Instant expiry = CLAIMED_AT.plus(LEASE);

        assertThat(transactions.claimDue(expiry, expiry.plus(LEASE), 1)).isEmpty();
        OutboxState recovered = findState(eventId);
        assertThat(recovered.attemptCount()).isEqualTo(1);
        assertThat(recovered.nextAttemptAt()).isEqualTo(expiry.plusSeconds(1));
        assertThat(recovered.deliveryStatus()).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT last_error_code FROM audit_source_evidence_outbox WHERE audit_event_id = ?
                """, String.class, eventId)).isEqualTo("RLC_DELIVERY_LEASE_EXPIRED");

        Instant retryAt = expiry.plusSeconds(1);
        var reclaimed = transactions.claimDue(retryAt, retryAt.plus(LEASE), 1);
        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.getFirst().auditEventId()).isEqualTo(eventId);
        assertThat(reclaimed.getFirst().claimToken()).isNotEqualTo(firstClaim.claimToken());
        assertThat(reclaimed.getFirst().attemptCount()).isEqualTo(1);
    }

    @Test
    void staleWorkerCannotPersistAnOutcomeAfterReclaim() {
        UUID eventId = UUID.randomUUID();
        insertPending(eventId, DUE_AT);
        var stale = transactions.claimDue(CLAIMED_AT, CLAIMED_AT.plus(LEASE), 1).getFirst();
        Instant expiry = CLAIMED_AT.plus(LEASE);
        transactions.claimDue(expiry, expiry.plus(LEASE), 1);
        Instant retryAt = expiry.plusSeconds(1);
        var current = transactions.claimDue(retryAt, retryAt.plus(LEASE), 1).getFirst();

        assertThat(transactions.markDelivered(eventId, stale.claimToken(), retryAt)).isFalse();
        assertThat(transactions.defer(eventId, stale.claimToken(), retryAt.plusSeconds(1),
                "STALE_TEST", false)).isFalse();
        assertThat(transactions.requireReconciliation(eventId, stale.claimToken(), "STALE_TEST")).isFalse();
        assertThat(findState(eventId).deliveryStatus()).isEqualTo("PENDING");
        assertThat(findState(eventId).attemptCount()).isEqualTo(1);
        assertThat(transactions.markDelivered(eventId, current.claimToken(), retryAt)).isTrue();
        assertThat(findState(eventId).deliveryStatus()).isEqualTo("DELIVERED");
    }

    @Test
    void duplicateAcknowledgementAfterLostResponseIsHarmless() {
        UUID eventId = UUID.randomUUID();
        insertPending(eventId, DUE_AT);
        when(deliveryClient.deliver(any()))
                .thenThrow(new ResourceAccessException("synthetic lost acknowledgement"))
                .thenReturn(new AuditSourceEvidenceDeliveryClient.DeliveryAcknowledgement(
                        eventId, 1, "DUPLICATE"));

        relay.deliverPendingEvidence();
        assertThat(findState(eventId).deliveryStatus()).isEqualTo("PENDING");
        assertThat(findState(eventId).attemptCount()).isEqualTo(1);
        jdbcTemplate.update("""
                UPDATE audit_source_evidence_outbox SET next_attempt_at = ? WHERE audit_event_id = ?
                """, Timestamp.from(DUE_AT), eventId);
        relay.deliverPendingEvidence();

        assertThat(findState(eventId).deliveryStatus()).isEqualTo("DELIVERED");
        assertThat(findState(eventId).attemptCount()).isEqualTo(1);
        var deliveredAt = jdbcTemplate.queryForObject("""
                SELECT delivered_at FROM audit_source_evidence_outbox WHERE audit_event_id = ?
                """, Timestamp.class, eventId);
        relay.deliverPendingEvidence();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT delivered_at FROM audit_source_evidence_outbox WHERE audit_event_id = ?
                """, Timestamp.class, eventId)).isEqualTo(deliveredAt);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM audit_source_evidence_outbox WHERE audit_event_id = ?
                """, Integer.class, eventId)).isEqualTo(1);
        verify(deliveryClient, times(2)).deliver(any());
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
