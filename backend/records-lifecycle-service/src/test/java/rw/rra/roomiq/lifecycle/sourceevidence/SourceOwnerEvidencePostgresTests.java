package rw.rra.roomiq.lifecycle.sourceevidence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import rw.rra.roomiq.lifecycle.retention.ExternalScheduleResult;
import rw.rra.roomiq.lifecycle.retention.HoldStatus;
import rw.rra.roomiq.lifecycle.retention.RetentionEvaluationResult;
import rw.rra.roomiq.lifecycle.retention.RetentionPolicyEvaluationService;
import rw.rra.roomiq.lifecycle.security.IdentityRetentionPolicyAuthorizationClient;

import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SourceOwnerEvidencePostgresTests {
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
    private SourceOwnerEvidenceIngestionService ingestionService;

    @Autowired
    private SourceOwnerEvidenceRepository evidenceRepository;

    @Autowired
    private RetentionPolicyEvaluationService evaluationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IdentityRetentionPolicyAuthorizationClient authorizationClient;

    @Test
    void persistsVersionedEvidenceWithServerObservationTimeAndAppendOnlyPrivileges() {
        SourceOwnerEvidence evidence = evidence("identity-service", "AUTHENTICATION", "record-persist",
                UUID.randomUUID(), 1, null, Instant.parse("2024-01-02T00:00:00Z"));

        SourceOwnerEvidenceIngestionResult result = ingestionService.ingest("identity-service", evidence);

        assertThat(result.outcome()).isEqualTo(SourceOwnerEvidenceOutcome.ACCEPTED);
        assertThat(result.evidenceSha256()).hasSize(64);
        assertThat(result.receivedAt()).isNotNull();
        assertThat(evidenceRepository.findLatestAccepted("identity-service", "AUTHENTICATION", "record-persist"))
                .get()
                .satisfies(receipt -> {
                    assertThat(receipt.evidence().contractVersion()).isEqualTo(1);
                    assertThat(receipt.authenticatedSourceService()).isEqualTo("identity-service");
                    assertThat(Duration.between(receipt.receivedAt(), result.receivedAt()).abs())
                            .isLessThanOrEqualTo(Duration.ofNanos(1_000));
                });
        assertThat(jdbcTemplate.queryForObject("""
                SELECT has_table_privilege(current_user,
                    'public.lifecycle_source_evidence_receipt', 'UPDATE')
                """, Boolean.class)).isFalse();
    }

    @Test
    void exactDuplicateDeliveryIsIdempotentAndDoesNotCreateAnotherAcceptedRevision() {
        SourceOwnerEvidence evidence = evidence("identity-service", "AUTHENTICATION", "record-duplicate",
                UUID.randomUUID(), 1, null, Instant.parse("2024-01-02T00:00:00Z"));

        assertThat(ingestionService.ingest("identity-service", evidence).outcome())
                .isEqualTo(SourceOwnerEvidenceOutcome.ACCEPTED);
        assertThat(ingestionService.ingest("identity-service", evidence).outcome())
                .isEqualTo(SourceOwnerEvidenceOutcome.DUPLICATE);
        assertThat(countReceipts(evidence)).isEqualTo(2);
        assertThat(countAccepted(evidence)).isEqualTo(1);
    }

    @Test
    void higherRevisionMustSupersedePreviousEvidenceAndBecomesCurrent() {
        UUID firstEvent = UUID.randomUUID();
        SourceOwnerEvidence first = evidence("identity-service", "AUTHENTICATION", "record-correction",
                firstEvent, 1, null, Instant.parse("2024-01-02T00:00:00Z"));
        SourceOwnerEvidence correction = evidence("identity-service", "AUTHENTICATION", "record-correction",
                UUID.randomUUID(), 2, firstEvent, Instant.parse("2024-01-03T00:00:00Z"));

        ingestionService.ingest("identity-service", first);
        assertThat(ingestionService.ingest("identity-service", correction).outcome())
                .isEqualTo(SourceOwnerEvidenceOutcome.ACCEPTED);
        assertThat(evidenceRepository.findLatestAccepted(
                "identity-service", "AUTHENTICATION", "record-correction").orElseThrow()
                .evidence().eventId()).isEqualTo(correction.eventId());
    }

    @Test
    void correctionCannotSupersedeAnUnknownEvent() {
        SourceOwnerEvidence evidence = evidence("identity-service", "AUTHENTICATION", "record-unknown-correction",
                UUID.randomUUID(), 2, UUID.randomUUID(), Instant.parse("2024-01-03T00:00:00Z"));

        assertThat(ingestionService.ingest("identity-service", evidence).outcome())
                .isEqualTo(SourceOwnerEvidenceOutcome.CONFLICTED);
        assertThat(evidenceRepository.findLatestAccepted(
                "identity-service", "AUTHENTICATION", evidence.recordId())).isEmpty();
    }

    @Test
    void staleOutOfOrderRevisionIsRecordedButCannotAcknowledgeCurrentEvidence() {
        UUID firstEvent = UUID.randomUUID();
        SourceOwnerEvidence first = evidence("identity-service", "AUTHENTICATION", "record-order",
                firstEvent, 1, null, Instant.parse("2024-01-02T00:00:00Z"));
        SourceOwnerEvidence second = evidence("identity-service", "AUTHENTICATION", "record-order",
                UUID.randomUUID(), 2, firstEvent, Instant.parse("2024-01-03T00:00:00Z"));
        SourceOwnerEvidence oldReplay = evidence("identity-service", "AUTHENTICATION", "record-order",
                UUID.randomUUID(), 1, firstEvent, Instant.parse("2024-01-02T00:00:00Z"));

        ingestionService.ingest("identity-service", first);
        ingestionService.ingest("identity-service", second);
        assertThat(ingestionService.ingest("identity-service", oldReplay).outcome())
                .isEqualTo(SourceOwnerEvidenceOutcome.STALE);

        var result = evaluationService.evaluateLatestEvidence("identity-service", "AUTHENTICATION", "record-order",
                Instant.parse("2040-01-01T00:00:00Z"), 1L, Map.of(), true, List.of(HoldStatus.RELEASED));
        assertThat(result.evaluation().reason()).isEqualTo(RetentionEvaluationResult.Reason.OWNER_UNACKNOWLEDGED);
        assertThat(result.sourceRevision()).isEqualTo(2);
    }

    @Test
    void conflictingSameRevisionBlocksEvaluationUntilNewerCorrection() {
        UUID firstEvent = UUID.randomUUID();
        SourceOwnerEvidence first = evidence("identity-service", "AUTHENTICATION", "record-conflict",
                firstEvent, 1, null, Instant.parse("2024-01-02T00:00:00Z"));
        SourceOwnerEvidence conflict = evidence("identity-service", "AUTHENTICATION", "record-conflict",
                UUID.randomUUID(), 1, firstEvent, Instant.parse("2024-01-04T00:00:00Z"));
        ingestionService.ingest("identity-service", first);

        assertThat(ingestionService.ingest("identity-service", conflict).outcome())
                .isEqualTo(SourceOwnerEvidenceOutcome.CONFLICTED);
        var blocked = evaluationService.evaluateLatestEvidence("identity-service", "AUTHENTICATION",
                "record-conflict", Instant.parse("2040-01-01T00:00:00Z"), 1L, Map.of(),
                true, List.of(HoldStatus.RELEASED));
        assertThat(blocked.evaluation().reason()).isEqualTo(RetentionEvaluationResult.Reason.SOURCE_EVIDENCE_CONFLICT);

        SourceOwnerEvidence correction = evidence("identity-service", "AUTHENTICATION", "record-conflict",
                UUID.randomUUID(), 2, firstEvent, Instant.parse("2024-01-05T00:00:00Z"));
        ingestionService.ingest("identity-service", correction);
        var current = evaluationService.evaluateLatestEvidence("identity-service", "AUTHENTICATION",
                "record-conflict", Instant.parse("2040-01-01T00:00:00Z"), 2L, Map.of(),
                true, List.of(HoldStatus.RELEASED));
        assertThat(current.evaluation().reason()).isEqualTo(RetentionEvaluationResult.Reason.NO_APPLICABLE_POLICY);
    }

    @Test
    void unauthenticatedSourceMismatchIsRejectedBeforePersistence() {
        SourceOwnerEvidence evidence = evidence("identity-service", "AUTHENTICATION", "record-owner",
                UUID.randomUUID(), 1, null, Instant.parse("2024-01-02T00:00:00Z"));

        assertThatThrownBy(() -> ingestionService.ingest("booking-service", evidence))
                .isInstanceOf(SourceOwnerEvidenceValidationException.class);
        assertThat(countReceipts(evidence)).isZero();
    }

    @Test
    void evidenceStoresAreIsolatedBySourceService() {
        SourceOwnerEvidence identity = evidence("identity-service", "AUTHENTICATION", "same-id",
                UUID.randomUUID(), 1, null, Instant.parse("2024-01-02T00:00:00Z"));
        SourceOwnerEvidence booking = evidence("booking-service", "BOOKING_REQUEST", "same-id",
                UUID.randomUUID(), 1, null, Instant.parse("2024-01-03T00:00:00Z"));

        ingestionService.ingest("identity-service", identity);
        ingestionService.ingest("booking-service", booking);

        assertThat(evidenceRepository.findLatestAccepted("identity-service", "AUTHENTICATION", "same-id"))
                .isPresent();
        assertThat(evidenceRepository.findLatestAccepted("booking-service", "BOOKING_REQUEST", "same-id"))
                .isPresent();
        assertThat(evidenceRepository.findLatestAccepted("identity-service", "BOOKING_REQUEST", "same-id"))
                .isEmpty();
    }

    @Test
    void evaluationUsesPersistedEvidenceAndReturnsEvidenceAndPolicyProvenance() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(1, ChronoUnit.DAYS);
        createAndActivatePolicy(actorId, policyId, start);
        SourceOwnerEvidence evidence = evidence("identity-service", "AUTHENTICATION", "record-evaluate",
                eventId, 1, null, start);
        ingestionService.ingest("identity-service", evidence);

        var result = evaluationService.evaluateLatestEvidence("identity-service", "AUTHENTICATION",
                "record-evaluate", start.plus(Duration.ofDays(3 * 365L)), 1L, Map.of(),
                true, List.of(HoldStatus.RELEASED));

        assertThat(result.sourceEvidenceEventId()).isEqualTo(eventId);
        assertThat(result.sourceRevision()).isEqualTo(1);
        assertThat(result.sourceEvidenceSha256()).hasSize(64);
        assertThat(result.evidenceReceivedAt()).isNotNull();
        assertThat(result.evaluation().eligible()).isTrue();
        assertThat(result.evaluation().policyVersionId()).isEqualTo(policyId);
    }

    @Test
    void absentOrUnacknowledgedEvidenceCannotProduceEligibility() {
        var absent = evaluationService.evaluateLatestEvidence("identity-service", "AUTHENTICATION",
                "missing-record", Instant.parse("2040-01-01T00:00:00Z"), null, Map.of(),
                true, List.of(HoldStatus.RELEASED));
        assertThat(absent.evaluation().reason()).isEqualTo(RetentionEvaluationResult.Reason.SOURCE_EVIDENCE_MISSING);

        SourceOwnerEvidence evidence = evidence("identity-service", "AUTHENTICATION", "record-unacknowledged",
                UUID.randomUUID(), 1, null, Instant.parse("2024-01-02T00:00:00Z"));
        ingestionService.ingest("identity-service", evidence);
        var staleAcknowledgement = evaluationService.evaluateLatestEvidence("identity-service", "AUTHENTICATION",
                "record-unacknowledged", Instant.parse("2040-01-01T00:00:00Z"), null, Map.of(),
                true, List.of(HoldStatus.RELEASED));
        assertThat(staleAcknowledgement.evaluation().eligible()).isFalse();
        assertThat(staleAcknowledgement.evaluation().reason())
                .isEqualTo(RetentionEvaluationResult.Reason.OWNER_UNACKNOWLEDGED);
    }

    @Test
    void categoryWithoutAnApplicablePolicyCannotBecomeEligible() {
        SourceOwnerEvidence evidence = evidence("identity-service", "AUTHENTICATION", "record-unknown-category",
                UUID.randomUUID(), 1, null, Instant.parse("2024-01-02T00:00:00Z"));
        evidence = new SourceOwnerEvidence(evidence.contractVersion(), evidence.sourceService(),
                evidence.recordType(), evidence.recordId(), evidence.eventId(), evidence.eventType(),
                evidence.eventVersion(), evidence.sourceRevision(), "UNMAPPED_CATEGORY",
                evidence.recordCreatedAt(), evidence.occurredAt(), evidence.authoritativeStartAt(),
                evidence.startBasisCode(), evidence.closedAt(), evidence.decisionAt(),
                evidence.transactionClosedAt(), evidence.actorSubject(), evidence.correlationId(),
                evidence.causationId(), evidence.supersedesEventId(), evidence.currentLifecycleStatus(),
                evidence.observedAt());
        ingestionService.ingest("identity-service", evidence);

        var result = evaluationService.evaluateLatestEvidence("identity-service", "AUTHENTICATION",
                evidence.recordId(), Instant.parse("2040-01-01T00:00:00Z"), 1L, Map.of(),
                true, List.of(HoldStatus.RELEASED));
        assertThat(result.evaluation().eligible()).isFalse();
        assertThat(result.evaluation().reason()).isEqualTo(RetentionEvaluationResult.Reason.NO_APPLICABLE_POLICY);
    }

    @Test
    void persistenceFailureRollsBackTheEvidenceReceipt() {
        UUID eventId = UUID.randomUUID();
        SourceOwnerEvidence evidence = evidence("identity-service", "AUTHENTICATION", "record-rollback",
                eventId, 1, null, Instant.parse("2024-01-02T00:00:00Z"));
        JdbcTemplate ownerJdbc = ownerJdbcTemplate();
        ownerJdbc.execute("""
                CREATE FUNCTION fail_selected_source_evidence_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.event_id = '%s'::uuid THEN
                        RAISE EXCEPTION 'intentional source evidence persistence failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(eventId));
        ownerJdbc.execute("""
                CREATE TRIGGER test_fail_selected_source_evidence_insert
                BEFORE INSERT ON lifecycle_source_evidence_receipt
                FOR EACH ROW EXECUTE FUNCTION fail_selected_source_evidence_insert()
                """);
        try {
            assertThatThrownBy(() -> ingestionService.ingest("identity-service", evidence))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM lifecycle_source_evidence_receipt WHERE event_id = ?",
                    Integer.class, eventId)).isZero();
        } finally {
            ownerJdbc.execute("DROP TRIGGER IF EXISTS test_fail_selected_source_evidence_insert "
                    + "ON lifecycle_source_evidence_receipt");
            ownerJdbc.execute("DROP FUNCTION IF EXISTS fail_selected_source_evidence_insert()");
        }
    }

    private void createAndActivatePolicy(UUID actorId, UUID policyId, Instant effectiveFrom) throws Exception {
        when(authorizationClient.authorize(any(), any(), any())).thenReturn(actorId);
        String create = """
                {
                  "policyVersionId": "%s",
                  "versionLabel": "evidence-%s",
                  "documentSha256": "%s",
                  "rules": [{
                    "ruleId": "%s",
                    "ruleCode": "AUTH",
                    "categoryCode": "ACCESS_AUTHENTICATION",
                    "ruleSource": "RRA_APPROVED",
                    "durationValue": 2,
                    "durationUnit": "YEAR",
                    "startBasisCode": "ORIGINAL_EVENT"
                  }]
                }
                """.formatted(policyId, policyId, "a".repeat(64), UUID.randomUUID());
        mockMvc.perform(post("/api/v1/retention/policies")
                        .with(jwt().jwt(token -> token.subject(actorId.toString())))
                        .header("Idempotency-Key", "evidence-create-" + policyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isCreated());

        String activate = """
                {
                  "approvalReference": "approval-%s",
                  "policyVersion": "version-%s",
                  "effectiveFrom": "%s",
                  "effectiveTo": null,
                  "approvingAuthorityRef": "authority-%s",
                  "legalValidationRef": "legal-%s",
                  "recordsValidationRef": "records-%s"
                }
                """.formatted(policyId, policyId, effectiveFrom, policyId, policyId, policyId);
        mockMvc.perform(post("/api/v1/retention/policies/{policyId}/activate", policyId)
                        .with(jwt().jwt(token -> token.subject(actorId.toString())))
                        .header("Idempotency-Key", "evidence-activate-" + policyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activate))
                .andExpect(status().isOk());
    }

    private int countReceipts(SourceOwnerEvidence evidence) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM lifecycle_source_evidence_receipt
                WHERE source_service = ? AND record_type = ? AND record_id = ?
                """, Integer.class, evidence.sourceService(), evidence.recordType(), evidence.recordId());
    }

    private int countAccepted(SourceOwnerEvidence evidence) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM lifecycle_source_evidence_receipt
                WHERE source_service = ? AND record_type = ? AND record_id = ? AND outcome = 'ACCEPTED'
                """, Integer.class, evidence.sourceService(), evidence.recordType(), evidence.recordId());
    }

    private JdbcTemplate ownerJdbcTemplate() {
        return new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    private static SourceOwnerEvidence evidence(String sourceService, String recordType, String recordId,
                                                UUID eventId, long revision, UUID supersedesEventId,
                                                Instant occurredAt) {
        return new SourceOwnerEvidence(1, sourceService, recordType, recordId, eventId,
                "RECORD_UPDATED", 1, revision, "ACCESS_AUTHENTICATION",
                Instant.parse("2024-01-01T00:00:00Z"), occurredAt, occurredAt, "ORIGINAL_EVENT",
                null, null, null, null, "correlation-1", null, supersedesEventId,
                "booking-service".equals(sourceService) ? "PENDING_APPROVAL" : null, occurredAt);
    }
}
