package rw.rra.roomiq.lifecycle.sourceevidence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.lifecycle.retention.ExternalScheduleResult;
import rw.rra.roomiq.lifecycle.retention.RetentionEvaluationResult;
import rw.rra.roomiq.lifecycle.retention.RetentionPolicyEvaluationService;
import rw.rra.roomiq.lifecycle.retention.SourceEvidenceEvaluationResult;
import rw.rra.roomiq.lifecycle.security.IdentityRetentionPolicyAuthorizationClient;
import rw.rra.roomiq.lifecycle.legalhold.LegalHoldRepository;

import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SourceOwnerEvidencePostgresTests {
    private static final String EVALUATION_TEST_CATEGORY = testCategory("TEST_EVALUATION_");
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
        registry.add("roomiq.lifecycle.source-evidence.booking-requests.allowed-categories",
                () -> "TEST_BOOKING_CATEGORY,TEST_BOOKING_START_BASIS_MISMATCH,"
                        + EVALUATION_TEST_CATEGORY);
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
    private LegalHoldRepository legalHoldRepository;

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
                Instant.parse("2040-01-01T00:00:00Z"), 1L, Map.of());
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
                "record-conflict", Instant.parse("2040-01-01T00:00:00Z"), 1L, Map.of());
        assertThat(blocked.evaluation().reason()).isEqualTo(RetentionEvaluationResult.Reason.SOURCE_EVIDENCE_CONFLICT);

        SourceOwnerEvidence correction = evidence("identity-service", "AUTHENTICATION", "record-conflict",
                UUID.randomUUID(), 2, firstEvent, Instant.parse("2024-01-05T00:00:00Z"));
        ingestionService.ingest("identity-service", correction);
        var current = evaluationService.evaluateLatestEvidence("identity-service", "AUTHENTICATION",
                "record-conflict", Instant.parse("2040-01-01T00:00:00Z"), 2L, Map.of());
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
        String bookingRecordId = UUID.randomUUID().toString();
        SourceOwnerEvidence booking = evidence("booking-service", "BOOKING_REQUEST", bookingRecordId,
                UUID.randomUUID(), 1, null, Instant.parse("2024-01-03T00:00:00Z"));

        ingestionService.ingest("identity-service", identity);
        ingestionService.ingest("booking-service", booking);

        assertThat(evidenceRepository.findLatestAccepted("identity-service", "AUTHENTICATION", "same-id"))
                .isPresent();
        assertThat(evidenceRepository.findLatestAccepted("booking-service", "BOOKING_REQUEST", bookingRecordId))
                .isPresent();
        assertThat(evidenceRepository.findLatestAccepted("identity-service", "BOOKING_REQUEST", "same-id"))
                .isEmpty();
    }

    @Test
    void bookingEvidenceEndpointRequiresTrustedServiceAuthority() throws Exception {
        mockMvc.perform(post("/internal/v1/source-evidence/booking-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/v1/source-evidence/booking-requests")
                        .header("Authorization", "Bearer malformed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/v1/source-evidence/booking-requests")
                        .with(jwt().jwt(token -> token.subject("booking-service"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_UNRELATED")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void bookingEvidenceEndpointAcceptsOnlyBoundedJsonRequests() throws Exception {
        mockMvc.perform(post("/internal/v1/source-evidence/booking-requests")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(post("/internal/v1/source-evidence/booking-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("x".repeat(64 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void authenticatedBookingEvidenceIsDurablyAcceptedByItsInternalEndpoint() throws Exception {
        Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");
        Instant occurredAt = Instant.parse("2024-01-02T00:00:00Z");
        UUID recordId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String requestBody = """
                {
                  "contractVersion": 1,
                  "sourceService": "booking-service",
                  "recordType": "BOOKING_REQUEST",
                  "recordId": "%s",
                  "eventId": "%s",
                  "eventType": "BOOKING_REQUEST_STATUS_CHANGED",
                  "eventVersion": 1,
                  "sourceRevision": 1,
                  "categoryCode": "TEST_BOOKING_CATEGORY",
                  "recordCreatedAt": "%s",
                  "occurredAt": "%s",
                  "authoritativeStartAt": "%s",
                  "startBasisCode": "RECORD_CREATED_AT",
                  "currentLifecycleStatus": "PENDING_APPROVAL",
                  "observedAt": "%s"
                }
                """.formatted(recordId, eventId, createdAt, occurredAt, createdAt, Instant.now());

        mockMvc.perform(post("/internal/v1/source-evidence/booking-requests")
                        .with(jwt().jwt(token -> token.subject("booking-service"))
                                .authorities(new SimpleGrantedAuthority(
                                        "SCOPE_BOOKING_SOURCE_EVIDENCE_PUBLISH")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());

        assertThat(evidenceRepository.findLatestAccepted(
                "booking-service", "BOOKING_REQUEST", recordId.toString())).isPresent();
    }

    @Test
    void evaluationUsesPersistedEvidenceAndReturnsEvidenceAndPolicyProvenance() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(1, ChronoUnit.DAYS);
        String recordId = UUID.randomUUID().toString();
        createAndActivatePolicy(actorId, policyId, start, EVALUATION_TEST_CATEGORY, "RECORD_CREATED_AT");
        SourceOwnerEvidence evidence = bookingEvidence(recordId, start, EVALUATION_TEST_CATEGORY);
        ingestionService.ingest("booking-service", evidence);

        Instant evaluatedAt = start.atZone(ZoneOffset.UTC).plusYears(2).toInstant();
        var result = evaluationService.evaluateLatestEvidence("booking-service", "BOOKING_REQUEST",
                recordId, evaluatedAt, 1L, Map.of());

        var repeated = evaluationService.evaluateLatestEvidence("booking-service", "BOOKING_REQUEST",
                recordId, evaluatedAt, 1L, Map.of());

        assertThat(result.sourceEvidenceEventId()).isEqualTo(evidence.eventId());
        assertThat(result.sourceRevision()).isEqualTo(1);
        assertThat(result.sourceEvidenceSha256()).hasSize(64);
        assertThat(result.evidenceReceivedAt()).isNotNull();
        assertThat(result.categoryCode()).isEqualTo(EVALUATION_TEST_CATEGORY);
        assertThat(result.authoritativeStartAt()).isEqualTo(start);
        assertThat(result.evaluationAt()).isEqualTo(evaluatedAt);
        assertThat(result.evaluation().eligible()).isTrue();
        assertThat(result.evaluation().retainUntil()).isEqualTo(evaluatedAt);
        assertThat(result.evaluation().policyVersionId()).isEqualTo(policyId);
        assertThat(result.evaluationId()).isNotNull();
        assertThat(repeated.evaluation().retainUntil()).isEqualTo(result.evaluation().retainUntil());
        assertThat(repeated.evaluationId()).isNotEqualTo(result.evaluationId());

        String provenance = jdbcTemplate.queryForObject("""
                SELECT provenance::text FROM lifecycle_retention_evaluation WHERE evaluation_id = ?
                """, String.class, result.evaluationId());
        assertThat(provenance).contains(evidence.eventId().toString(), EVALUATION_TEST_CATEGORY,
                policyId.toString(), "RECORD_CREATED_AT");
        assertThat(provenance).contains("\"applicableHolds\": []");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT has_table_privilege(current_user,
                    'public.lifecycle_retention_evaluation', 'UPDATE')
                """, Boolean.class)).isFalse();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE lifecycle_retention_evaluation SET evaluation_reason = 'RETENTION_EXPIRED'
                WHERE evaluation_id = ?
                """, result.evaluationId())).isInstanceOf(RuntimeException.class);
        UUID firstHoldId = UUID.randomUUID();
        UUID secondHoldId = UUID.randomUUID();
        createActiveCategoryHold(actorId, UUID.randomUUID(), firstHoldId, EVALUATION_TEST_CATEGORY);
        createActiveCategoryHold(actorId, UUID.randomUUID(), secondHoldId, EVALUATION_TEST_CATEGORY);

        var blockedByBoth = evaluationService.evaluateLatestEvidence("booking-service", "BOOKING_REQUEST",
                recordId, evaluatedAt, 1L, Map.of());
        assertThat(blockedByBoth.evaluation().eligible()).isFalse();
        assertThat(blockedByBoth.evaluation().reason()).isEqualTo(RetentionEvaluationResult.Reason.HOLD_BLOCKS_DISPOSAL);
        String holdsProvenance = jdbcTemplate.queryForObject("""
                SELECT provenance::text FROM lifecycle_retention_evaluation WHERE evaluation_id = ?
                """, String.class, blockedByBoth.evaluationId());
        assertThat(holdsProvenance).contains(firstHoldId.toString(), secondHoldId.toString());

        requestAndApproveRelease(firstHoldId, UUID.randomUUID(), UUID.randomUUID());
        var blockedByRemaining = evaluationService.evaluateLatestEvidence("booking-service", "BOOKING_REQUEST",
                recordId, evaluatedAt, 1L, Map.of());
        assertThat(blockedByRemaining.evaluation().eligible()).isFalse();
        assertThat(blockedByRemaining.evaluation().reason()).isEqualTo(RetentionEvaluationResult.Reason.HOLD_BLOCKS_DISPOSAL);
        String remainingHoldProvenance = jdbcTemplate.queryForObject("""
                SELECT provenance::text FROM lifecycle_retention_evaluation WHERE evaluation_id = ?
                """, String.class, blockedByRemaining.evaluationId());
        assertThat(remainingHoldProvenance).contains(secondHoldId.toString())
                .doesNotContain(firstHoldId.toString());

        requestAndApproveRelease(secondHoldId, UUID.randomUUID(), UUID.randomUUID());
        var eligibleAfterAllReleased = evaluationService.evaluateLatestEvidence("booking-service", "BOOKING_REQUEST",
                recordId, evaluatedAt, 1L, Map.of());
        assertThat(eligibleAfterAllReleased.evaluation().eligible()).isTrue();

        SourceOwnerEvidence mismatchedBasis = bookingEvidence(UUID.randomUUID().toString(), start,
                "TEST_BOOKING_START_BASIS_MISMATCH");
        ingestionService.ingest("booking-service", mismatchedBasis);
        var rejectedBasis = evaluationService.evaluateLatestEvidence("booking-service", "BOOKING_REQUEST",
                mismatchedBasis.recordId(), evaluatedAt, 1L, Map.of());
        assertThat(rejectedBasis.evaluation().eligible()).isFalse();
        assertThat(rejectedBasis.evaluation().reason()).isEqualTo(RetentionEvaluationResult.Reason.RULE_START_MISSING);
    }

    @Test
    void absentOrUnacknowledgedEvidenceCannotProduceEligibility() {
        var absent = evaluationService.evaluateLatestEvidence("identity-service", "AUTHENTICATION",
                "missing-record", Instant.parse("2040-01-01T00:00:00Z"), null, Map.of());
        assertThat(absent.evaluation().reason()).isEqualTo(RetentionEvaluationResult.Reason.SOURCE_EVIDENCE_MISSING);

        SourceOwnerEvidence evidence = evidence("identity-service", "AUTHENTICATION", "record-unacknowledged",
                UUID.randomUUID(), 1, null, Instant.parse("2024-01-02T00:00:00Z"));
        ingestionService.ingest("identity-service", evidence);
        var staleAcknowledgement = evaluationService.evaluateLatestEvidence("identity-service", "AUTHENTICATION",
                "record-unacknowledged", Instant.parse("2040-01-01T00:00:00Z"), null, Map.of());
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
                evidence.recordId(), Instant.parse("2040-01-01T00:00:00Z"), 1L, Map.of());
        assertThat(result.evaluation().eligible()).isFalse();
        assertThat(result.evaluation().reason()).isEqualTo(RetentionEvaluationResult.Reason.NO_APPLICABLE_POLICY);
    }

    @Test
    void legalHoldLifecycleEnforcesSeparationOfDutiesAndKeepsAppendOnlyHistory() throws Exception {
        UUID initiator = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        UUID releaseRequester = UUID.randomUUID();
        UUID releaseApprover = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        String create = categoryHoldRequest(holdId, "SYNTHETIC_TEST_ONLY-HOLD-LIFECYCLE");

        mockMvc.perform(post("/api/v1/legal-holds")
                        .with(holdActor(initiator))
                        .header("Idempotency-Key", "hold-create-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/legal-holds")
                        .with(holdActor(initiator))
                        .header("Idempotency-Key", "hold-create-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/legal-holds/{holdId}/approve", holdId)
                        .with(holdActor(approver))
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "hold-invalid-approve-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evidenceReference("SYNTHETIC_TEST_ONLY-approval")))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/legal-holds/{holdId}/submit", holdId)
                        .with(holdActor(initiator))
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "hold-submit-" + holdId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/legal-holds/{holdId}/approve", holdId)
                        .with(holdActor(initiator))
                        .header("If-Match", "2")
                        .header("Idempotency-Key", "hold-self-approve-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evidenceReference("SYNTHETIC_TEST_ONLY-approval")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/legal-holds/{holdId}/approve", holdId)
                        .with(holdActor(approver))
                        .header("If-Match", "2")
                        .header("Idempotency-Key", "hold-approve-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evidenceReference("SYNTHETIC_TEST_ONLY-approval")))
                .andExpect(status().isOk());

        String amendment = """
                {"reason":"Synthetic amended reason","scope":{"type":"CATEGORY",
                "categoryCode":"TEST_BOOKING_CATEGORY"},
                "evidenceReference":"SYNTHETIC_TEST_ONLY-amend"}
                """;
        mockMvc.perform(put("/api/v1/legal-holds/{holdId}/amend", holdId)
                        .with(holdActor(approver))
                        .header("If-Match", "3")
                        .header("Idempotency-Key", "hold-amend-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendment))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/legal-holds/{holdId}/amend", holdId)
                        .with(holdActor(approver))
                        .header("If-Match", "3")
                        .header("Idempotency-Key", "hold-stale-amend-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendment))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/legal-holds/{holdId}/request-release", holdId)
                        .with(holdActor(initiator))
                        .header("If-Match", "4")
                        .header("Idempotency-Key", "hold-self-release-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evidenceReference("SYNTHETIC_TEST_ONLY-release-request")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/legal-holds/{holdId}/request-release", holdId)
                        .with(holdActor(releaseRequester))
                        .header("If-Match", "4")
                        .header("Idempotency-Key", "hold-release-request-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evidenceReference("SYNTHETIC_TEST_ONLY-release-request")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/legal-holds/{holdId}/release", holdId)
                        .with(holdActor(releaseRequester))
                        .header("If-Match", "5")
                        .header("Idempotency-Key", "hold-self-release-approve-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evidenceReference("SYNTHETIC_TEST_ONLY-release-verification")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/legal-holds/{holdId}/release", holdId)
                        .with(holdActor(releaseApprover))
                        .header("If-Match", "5")
                        .header("Idempotency-Key", "hold-release-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evidenceReference("SYNTHETIC_TEST_ONLY-release-verification")))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM lifecycle_control_event WHERE aggregate_type = 'HOLD' AND aggregate_id = ?
                """, Integer.class, holdId)).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT has_table_privilege(current_user, 'public.lifecycle_control_event', 'UPDATE')
                """, Boolean.class)).isFalse();
        JdbcTemplate ownerJdbc = ownerJdbcTemplate();
        assertThatThrownBy(() -> ownerJdbc.update("""
                UPDATE lifecycle_control_event SET metadata = '{}'::jsonb
                WHERE aggregate_type = 'HOLD' AND aggregate_id = ?
                """, holdId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ownerJdbc.update("""
                DELETE FROM lifecycle_control_event
                WHERE aggregate_type = 'HOLD' AND aggregate_id = ?
                """, holdId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ownerJdbc.execute("TRUNCATE lifecycle_control_event"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ownerJdbc.update("""
                DELETE FROM lifecycle_legal_hold WHERE hold_id = ?
                """, holdId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ownerJdbc.execute("TRUNCATE lifecycle_legal_hold"))
                .isInstanceOf(RuntimeException.class);
        String amendmentHistory = jdbcTemplate.queryForObject("""
                SELECT metadata::text FROM lifecycle_control_event
                WHERE aggregate_type = 'HOLD' AND aggregate_id = ? AND event_type = 'HOLD_AMENDED'
                """, String.class, holdId);
        assertThat(amendmentHistory).contains("Synthetic test scope", "Synthetic amended reason");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM lifecycle_legal_hold WHERE hold_id = ?
                """, String.class, holdId)).isEqualTo("RELEASED");
    }

    @Test
    void legalHoldApiRejectsUnauthenticatedAndIdentityDeniedCallers() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        String request = categoryHoldRequest(holdId, "SYNTHETIC_TEST_ONLY-DENIED");
        when(authorizationClient.authorizeLegalHolds(any(), eq(actor), any()))
                .thenThrow(new DomainException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied"));

        mockMvc.perform(post("/api/v1/legal-holds")
                        .with(jwt().jwt(token -> token.subject(actor.toString())))
                        .header("Idempotency-Key", "hold-denied-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/legal-holds")
                        .header("Idempotency-Key", "hold-anonymous-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM lifecycle_legal_hold WHERE hold_id = ?
                """, Integer.class, holdId)).isZero();
    }

    @Test
    void concurrentAmendmentsWithOneExpectedVersionCannotLoseAnUpdate() throws Exception {
        UUID initiator = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        createActiveCategoryHold(initiator, approver, holdId, "TEST_BOOKING_CATEGORY");
        RequestPostProcessor authorization = holdActor(approver);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> amendStatus(holdId, authorization, start,
                    "concurrent amendment one"));
            Future<Integer> second = executor.submit(() -> amendStatus(holdId, authorization, start,
                    "concurrent amendment two"));
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT version FROM lifecycle_legal_hold WHERE hold_id = ?
                    """, Long.class, holdId)).isEqualTo(4L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recordScopedHoldMatchesOnlyItsExactSourceRecord() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        String recordId = UUID.randomUUID().toString();
        String create = """
                {
                  "holdId":"%s",
                  "holdReference":"SYNTHETIC_TEST_ONLY-RECORD-%s",
                  "reason":"Synthetic test scope",
                  "scope":{"type":"RECORD","sourceService":"booking-service",
                           "recordType":"BOOKING_REQUEST","recordId":"%s"},
                  "supportingEvidenceReference":"SYNTHETIC_TEST_ONLY-record-evidence"
                }
                """.formatted(holdId, holdId, recordId);
        mockMvc.perform(post("/api/v1/legal-holds")
                        .with(holdActor(actor))
                        .header("Idempotency-Key", "record-hold-create-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/legal-holds/{holdId}/submit", holdId)
                        .with(holdActor(actor))
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "record-hold-submit-" + holdId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/legal-holds/{holdId}/approve", holdId)
                        .with(holdActor(approver))
                        .header("If-Match", "2")
                        .header("Idempotency-Key", "record-hold-approve-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evidenceReference("SYNTHETIC_TEST_ONLY-record-approval")))
                .andExpect(status().isOk());

        assertThat(legalHoldRepository.findApplicable("booking-service", "BOOKING_REQUEST", recordId,
                "TEST_BOOKING_CATEGORY").applicableHolds()).hasSize(1);
        assertThat(legalHoldRepository.findApplicable("booking-service", "BOOKING_REQUEST",
                UUID.randomUUID().toString(), "TEST_BOOKING_CATEGORY").applicableHolds()).isEmpty();
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

    private void createAndActivatePolicy(UUID actorId, UUID policyId, Instant effectiveFrom,
                                         String categoryCode, String startBasisCode) throws Exception {
        when(authorizationClient.authorize(any(), any(), any())).thenReturn(actorId);
        String create = """
                {
                  "policyVersionId": "%s",
                  "versionLabel": "SYNTHETIC_TEST_ONLY-%s",
                  "documentSha256": "%s",
                  "rules": [
                    {
                      "ruleId": "%s",
                      "ruleCode": "AUTH",
                      "categoryCode": "%s",
                      "ruleSource": "RRA_APPROVED",
                      "durationValue": 2,
                      "durationUnit": "YEAR",
                      "startBasisCode": "%s"
                    },
                    {
                      "ruleId": "%s",
                      "ruleCode": "MISMATCH_BASIS",
                      "categoryCode": "TEST_BOOKING_START_BASIS_MISMATCH",
                      "ruleSource": "RRA_APPROVED",
                      "durationValue": 2,
                      "durationUnit": "YEAR",
                      "startBasisCode": "OCCURRED_AT"
                    }
                  ]
                }
                """.formatted(policyId, policyId, "a".repeat(64), UUID.randomUUID(), categoryCode,
                startBasisCode, UUID.randomUUID());
        mockMvc.perform(post("/api/v1/retention/policies")
                        .with(jwt().jwt(token -> token.subject(actorId.toString())))
                        .header("Idempotency-Key", "evidence-create-" + policyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(create))
                .andExpect(status().isCreated());

        String activate = """
                {
                  "approvalReference": "SYNTHETIC_TEST_ONLY-approval-%s",
                  "policyVersion": "SYNTHETIC_TEST_ONLY-version-%s",
                  "effectiveFrom": "%s",
                  "effectiveTo": null,
                  "approvingAuthorityRef": "SYNTHETIC_TEST_ONLY-authority-%s",
                  "legalValidationRef": "SYNTHETIC_TEST_ONLY-legal-%s",
                  "recordsValidationRef": "SYNTHETIC_TEST_ONLY-records-%s"
                }
                """.formatted(policyId, policyId, effectiveFrom, policyId, policyId, policyId);
        mockMvc.perform(post("/api/v1/retention/policies/{policyId}/activate", policyId)
                        .with(jwt().jwt(token -> token.subject(actorId.toString())))
                        .header("Idempotency-Key", "evidence-activate-" + policyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activate))
                .andExpect(status().isOk());
    }

    private void createActiveCategoryHold(UUID initiator, UUID approver, UUID holdId, String categoryCode)
            throws Exception {
        mockMvc.perform(post("/api/v1/legal-holds")
                        .with(holdActor(initiator))
                        .header("Idempotency-Key", "category-hold-create-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(categoryHoldRequest(holdId, "SYNTHETIC_TEST_ONLY-CATEGORY-" + holdId,
                                categoryCode)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/legal-holds/{holdId}/submit", holdId)
                        .with(holdActor(initiator))
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "category-hold-submit-" + holdId))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/legal-holds/{holdId}/approve", holdId)
                        .with(holdActor(approver))
                        .header("If-Match", "2")
                        .header("Idempotency-Key", "category-hold-approve-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evidenceReference("SYNTHETIC_TEST_ONLY-category-approval")))
                .andExpect(status().isOk());
    }

    private void requestAndApproveRelease(UUID holdId, UUID releaseRequester, UUID releaseApprover)
            throws Exception {
        mockMvc.perform(post("/api/v1/legal-holds/{holdId}/request-release", holdId)
                        .with(holdActor(releaseRequester))
                        .header("If-Match", "3")
                        .header("Idempotency-Key", "category-hold-release-request-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evidenceReference("SYNTHETIC_TEST_ONLY-release-request")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/legal-holds/{holdId}/release", holdId)
                        .with(holdActor(releaseApprover))
                        .header("If-Match", "4")
                        .header("Idempotency-Key", "category-hold-release-" + holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evidenceReference("SYNTHETIC_TEST_ONLY-release-verification")))
                .andExpect(status().isOk());
    }

    private RequestPostProcessor holdActor(UUID actor) {
        when(authorizationClient.authorizeLegalHolds(any(), eq(actor), any())).thenReturn(actor);
        return jwt().jwt(token -> token.subject(actor.toString()));
    }

    private int amendStatus(UUID holdId, RequestPostProcessor authorization, CountDownLatch start, String reason)
            throws Exception {
        if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent amendment test did not start");
        }
        return mockMvc.perform(put("/api/v1/legal-holds/{holdId}/amend", holdId)
                        .with(authorization)
                        .header("If-Match", "3")
                        .header("Idempotency-Key", "concurrent-" + reason.replace(' ', '-'))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"%s","scope":{"type":"CATEGORY",
                                "categoryCode":"TEST_BOOKING_CATEGORY"},
                                "evidenceReference":"SYNTHETIC_TEST_ONLY-concurrent"}
                                """.formatted(reason)))
                .andReturn().getResponse().getStatus();
    }

    private static String categoryHoldRequest(UUID holdId, String holdReference) {
        return categoryHoldRequest(holdId, holdReference, "TEST_BOOKING_CATEGORY");
    }

    private static String categoryHoldRequest(UUID holdId, String holdReference, String categoryCode) {
        return """
                {
                  "holdId":"%s",
                  "holdReference":"%s",
                  "reason":"Synthetic test scope",
                  "scope":{"type":"CATEGORY","categoryCode":"%s"},
                  "supportingEvidenceReference":"SYNTHETIC_TEST_ONLY-supporting-evidence"
                }
                """.formatted(holdId, holdReference, categoryCode);
    }

    private static String evidenceReference(String reference) {
        return "{\"evidenceReference\":\"" + reference + "\"}";
    }

    private static String testCategory(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
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
                "booking-service".equals(sourceService) ? "BOOKING_REQUEST_STATUS_CHANGED" : "RECORD_UPDATED",
                1, revision,
                "booking-service".equals(sourceService) ? "TEST_BOOKING_CATEGORY" : "ACCESS_AUTHENTICATION",
                Instant.parse("2024-01-01T00:00:00Z"), occurredAt, occurredAt, "ORIGINAL_EVENT",
                null, null, null, null, "correlation-1", null, supersedesEventId,
                "booking-service".equals(sourceService) ? "PENDING_APPROVAL" : null, occurredAt);
    }

    private static SourceOwnerEvidence bookingEvidence(String recordId, Instant start) {
        return bookingEvidence(recordId, start, "TEST_BOOKING_CATEGORY");
    }

    private static SourceOwnerEvidence bookingEvidence(String recordId, Instant start, String categoryCode) {
        return new SourceOwnerEvidence(1, "booking-service", "BOOKING_REQUEST", recordId,
                UUID.randomUUID(), "BOOKING_REQUEST_STATUS_CHANGED", 1, 1, categoryCode,
                start, start, start, "RECORD_CREATED_AT", null, null, null, null,
                "booking-evidence-test", null, null, "PENDING_APPROVAL", start);
    }
}
