package rw.rra.roomiq.lifecycle.sourceevidence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import rw.rra.roomiq.lifecycle.retention.ExternalScheduleResult;
import rw.rra.roomiq.lifecycle.retention.HoldStatus;
import rw.rra.roomiq.lifecycle.retention.RetentionEvaluationResult;
import rw.rra.roomiq.lifecycle.retention.RetentionPolicyEvaluationService;
import rw.rra.roomiq.lifecycle.retention.SourceEvidenceEvaluationResult;
import rw.rra.roomiq.lifecycle.holdcontrol.CreateRecordsHoldRequest;
import rw.rra.roomiq.lifecycle.holdcontrol.RecordsLegalHold;
import rw.rra.roomiq.lifecycle.holdcontrol.RecordsHoldRegistryService;
import rw.rra.roomiq.lifecycle.holdcontrol.RecordsHoldInstructionOutboxRepository;
import rw.rra.roomiq.lifecycle.holdcontrol.RecordsHoldAcknowledgementService;
import rw.rra.roomiq.lifecycle.holdcontrol.BookingHoldAcknowledgement;
import rw.rra.roomiq.lifecycle.holdcontrol.RecordsHoldStatus;
import rw.rra.roomiq.lifecycle.holdcontrol.ReleaseRecordsHoldRequest;
import rw.rra.roomiq.lifecycle.holdcontrol.VerifyRecordsHoldReleaseRequest;
import rw.rra.roomiq.lifecycle.security.IdentityRetentionPolicyAuthorizationClient;
import rw.rra.roomiq.common.web.DomainException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        registry.add("roomiq.lifecycle.source-evidence.booking-requests.allowed-categories",
                () -> "TEST_BOOKING_CATEGORY,TEST_BOOKING_START_BASIS_MISMATCH,TEST_BOOKING_HOLD_CATEGORY");
        registry.add("roomiq.lifecycle.booking-hold-instructions.enabled", () -> "false");
    }

    @Autowired
    private SourceOwnerEvidenceIngestionService ingestionService;

    @Autowired
    private SourceOwnerEvidenceRepository evidenceRepository;

    @Autowired
    private RetentionPolicyEvaluationService evaluationService;

    @Autowired
    private RecordsHoldRegistryService recordsHoldRegistryService;

    @Autowired
    private RecordsHoldInstructionOutboxRepository holdInstructionOutbox;

    @Autowired
    private RecordsHoldAcknowledgementService holdAcknowledgements;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
        createAndActivatePolicy(actorId, policyId, start, "TEST_BOOKING_CATEGORY", "RECORD_CREATED_AT");
        SourceOwnerEvidence evidence = bookingEvidence(recordId, start);
        ingestionService.ingest("booking-service", evidence);

        Instant evaluatedAt = start.atZone(java.time.ZoneOffset.UTC).plusYears(2).toInstant();
        var result = evaluationService.evaluateLatestEvidence("booking-service", "BOOKING_REQUEST",
                recordId, evaluatedAt, 1L, Map.of());

        var repeated = evaluationService.evaluateLatestEvidence("booking-service", "BOOKING_REQUEST",
                recordId, evaluatedAt, 1L, Map.of());

        assertThat(result.sourceEvidenceEventId()).isEqualTo(evidence.eventId());
        assertThat(result.sourceRevision()).isEqualTo(1);
        assertThat(result.sourceEvidenceSha256()).hasSize(64);
        assertThat(result.evidenceReceivedAt()).isNotNull();
        assertThat(result.categoryCode()).isEqualTo("TEST_BOOKING_CATEGORY");
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
        assertThat(provenance).contains(evidence.eventId().toString(), "TEST_BOOKING_CATEGORY",
                policyId.toString(), "RECORD_CREATED_AT");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT has_table_privilege(current_user,
                    'public.lifecycle_retention_evaluation', 'UPDATE')
                """, Boolean.class)).isFalse();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE lifecycle_retention_evaluation SET evaluation_reason = 'RETENTION_EXPIRED'
                WHERE evaluation_id = ?
                """, result.evaluationId())).isInstanceOf(RuntimeException.class);
        SourceOwnerEvidence mismatchedBasis = bookingEvidence(UUID.randomUUID().toString(), start,
                "TEST_BOOKING_START_BASIS_MISMATCH");
        ingestionService.ingest("booking-service", mismatchedBasis);
        var rejectedBasis = evaluationService.evaluateLatestEvidence("booking-service", "BOOKING_REQUEST",
                mismatchedBasis.recordId(), evaluatedAt, 1L, Map.of());
        assertThat(rejectedBasis.evaluation().eligible()).isFalse();
        assertThat(rejectedBasis.evaluation().reason()).isEqualTo(RetentionEvaluationResult.Reason.RULE_START_MISSING);
    }

    @Test
    void legalHoldManagementEndpointRequiresTrustedIdentityAuthorization() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        String recordId = UUID.randomUUID().toString();
        String request = """
                {
                  "holdId": "%s",
                  "sourceService": "booking-service",
                  "recordType": "BOOKING_REQUEST",
                  "recordId": "%s",
                  "instructionReference": "TEST_INSTRUCTION-1"
                }
                """.formatted(holdId, recordId);

        mockMvc.perform(post("/api/v1/legal-holds")
                        .header("Idempotency-Key", "hold-auth-unauthenticated")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());

        when(authorizationClient.authorizeRecordsHolds(any(), eq(actorId), any())).thenReturn(actorId);
        mockMvc.perform(post("/api/v1/legal-holds")
                        .with(jwt().jwt(token -> token.subject(actorId.toString())))
                        .header("Idempotency-Key", "hold-auth-authorized")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("PENDING_BLOCK"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.createdByUserId").value(actorId.toString()));

        UUID deniedActor = UUID.randomUUID();
        when(authorizationClient.authorizeRecordsHolds(any(), eq(deniedActor), any()))
                .thenThrow(new DomainException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied"));
        mockMvc.perform(post("/api/v1/legal-holds")
                        .with(jwt().jwt(token -> token.subject(deniedActor.toString())))
                        .header("Idempotency-Key", "hold-auth-denied")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
    }

    @Test
    void persistedOverlappingHoldsBlockEvaluationUntilEachReleaseIsIndependentlyVerified() throws Exception {
        UUID policyActor = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(10, ChronoUnit.DAYS);
        String recordId = UUID.randomUUID().toString();
        String categoryCode = "TEST_BOOKING_HOLD_CATEGORY";
        createAndActivatePolicy(policyActor, policyId, start, categoryCode, "RECORD_CREATED_AT");
        ingestionService.ingest("booking-service", bookingEvidence(recordId, start, categoryCode));
        Instant evaluatedAt = start.atZone(java.time.ZoneOffset.UTC).plusYears(2).toInstant();

        assertThat(evaluationService.evaluateLatestEvidence("booking-service", "BOOKING_REQUEST",
                recordId, evaluatedAt, 1L, Map.of()).evaluation().eligible()).isTrue();

        UUID initiator = UUID.randomUUID();
        UUID verifier = UUID.randomUUID();
        UUID firstHoldId = UUID.randomUUID();
        CreateRecordsHoldRequest firstRequest = new CreateRecordsHoldRequest(firstHoldId,
                "booking-service", "BOOKING_REQUEST", recordId, "TEST_INSTRUCTION-1");
        RecordsLegalHold firstHold = recordsHoldRegistryService.create(
                firstRequest, initiator, "hold-create-1", "hold-correlation-1");
        assertThat(firstHold.status()).isEqualTo(RecordsHoldStatus.PENDING_BLOCK);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT has_table_privilege(current_user, 'public.lifecycle_legal_hold', 'DELETE')
                """, Boolean.class)).isFalse();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE lifecycle_legal_hold SET status = 'RELEASED' WHERE hold_id = ?
                """, firstHoldId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM lifecycle_legal_hold WHERE hold_id = ?
                """, firstHoldId)).isInstanceOf(DataAccessException.class);
        RecordsLegalHold duplicate = recordsHoldRegistryService.create(
                firstRequest, initiator, "hold-create-1", "hold-correlation-1");
        assertThat(duplicate.version()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM lifecycle_control_event
                WHERE aggregate_type = 'HOLD' AND aggregate_id = ?
                """, Integer.class, firstHoldId)).isEqualTo(1);

        var blockedByPending = evaluationService.evaluateLatestEvidence("booking-service", "BOOKING_REQUEST",
                recordId, evaluatedAt, 1L, Map.of());
        assertThat(blockedByPending.evaluation().eligible()).isFalse();
        assertThat(blockedByPending.evaluation().reason())
                .isEqualTo(RetentionEvaluationResult.Reason.HOLD_BLOCKS_DISPOSAL);
        String provenance = jdbcTemplate.queryForObject("""
                SELECT provenance::text FROM lifecycle_retention_evaluation WHERE evaluation_id = ?
                """, String.class, blockedByPending.evaluationId());
        assertThat(provenance).contains("PENDING_BLOCK", firstHoldId.toString());

        UUID secondHoldId = UUID.randomUUID();
        RecordsLegalHold overlapping = recordsHoldRegistryService.create(
                new CreateRecordsHoldRequest(secondHoldId, "booking-service", "BOOKING_REQUEST",
                        recordId, "TEST_INSTRUCTION-2"),
                verifier, "hold-create-2", "hold-correlation-2");
        assertThat(overlapping.status()).isEqualTo(RecordsHoldStatus.CONFLICTED);

        recordsHoldRegistryService.requestRelease(firstHoldId,
                new ReleaseRecordsHoldRequest("TEST_RELEASE-1"), initiator,
                "hold-release-1", "hold-correlation-3");
        assertThat(evaluationService.evaluateLatestEvidence("booking-service", "BOOKING_REQUEST",
                recordId, evaluatedAt, 1L, Map.of()).evaluation().reason())
                .isEqualTo(RetentionEvaluationResult.Reason.HOLD_BLOCKS_DISPOSAL);
        assertThatThrownBy(() -> recordsHoldRegistryService.verifyRelease(firstHoldId,
                new VerifyRecordsHoldReleaseRequest("TEST_VERIFICATION-SELF"), initiator,
                "hold-verify-self", "hold-correlation-4"))
                .isInstanceOf(DomainException.class);
        recordsHoldRegistryService.verifyRelease(firstHoldId,
                new VerifyRecordsHoldReleaseRequest("TEST_VERIFICATION-1"), verifier,
                "hold-verify-1", "hold-correlation-5");
        assertThat(recordsHoldRegistryService.get(firstHoldId).status())
                .isEqualTo(RecordsHoldStatus.RELEASE_ENFORCEMENT_PENDING);
        acknowledgeDueHoldInstructions();
        assertThat(evaluationService.evaluateLatestEvidence("booking-service", "BOOKING_REQUEST",
                recordId, evaluatedAt, 1L, Map.of()).evaluation().eligible()).isFalse();

        recordsHoldRegistryService.requestRelease(secondHoldId,
                new ReleaseRecordsHoldRequest("TEST_RELEASE-2"), verifier,
                "hold-release-2", "hold-correlation-6");
        recordsHoldRegistryService.verifyRelease(secondHoldId,
                new VerifyRecordsHoldReleaseRequest("TEST_VERIFICATION-2"), initiator,
                "hold-verify-2", "hold-correlation-7");
        acknowledgeDueHoldInstructions();
        assertThat(evaluationService.evaluateLatestEvidence("booking-service", "BOOKING_REQUEST",
                recordId, evaluatedAt, 1L, Map.of()).evaluation().eligible()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM lifecycle_control_event
                WHERE aggregate_type = 'HOLD' AND aggregate_id = ?
                """, Integer.class, firstHoldId)).isEqualTo(5);
    }

    @Test
    void holdInstructionOutboxRetriesInOrderAndOnlyAcknowledgedReleaseCanCompleteRelease() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        String recordId = UUID.randomUUID().toString();
        recordsHoldRegistryService.create(new CreateRecordsHoldRequest(
                holdId, "booking-service", "BOOKING_REQUEST", recordId, "TEST_INSTRUCTION-OUTBOX"),
                actorId, "hold-outbox-create", "hold-outbox-correlation");

        var apply = holdInstructionOutbox.claimDue(Instant.now().plusSeconds(1),
                Instant.now().plusSeconds(31), 10);
        assertThat(apply).hasSize(1);
        var applyInstruction = apply.getFirst();
        assertThat(applyInstruction.instruction().operation()).isEqualTo("APPLY");
        assertThat(applyInstruction.attemptCount()).isEqualTo(1);
        holdAcknowledgements.accept(applyInstruction, acknowledgement(applyInstruction, "ACTIVE"));

        recordsHoldRegistryService.requestRelease(holdId, new ReleaseRecordsHoldRequest("TEST_RELEASE-OUTBOX"),
                actorId, "hold-outbox-release", "hold-outbox-correlation");
        recordsHoldRegistryService.verifyRelease(holdId,
                new VerifyRecordsHoldReleaseRequest("TEST_VERIFY-OUTBOX"), UUID.randomUUID(),
                "hold-outbox-verify", "hold-outbox-correlation");
        var release = holdInstructionOutbox.claimDue(Instant.now().plusSeconds(1),
                Instant.now().plusSeconds(31), 10);
        assertThat(release).hasSize(1);
        assertThat(release.getFirst().instruction().operation()).isEqualTo("RELEASE");
        assertThat(release.getFirst().instruction().instructionRevision()).isEqualTo(2);
        assertThat(recordsHoldRegistryService.get(holdId).status())
                .isEqualTo(RecordsHoldStatus.RELEASE_ENFORCEMENT_PENDING);

        holdAcknowledgements.accept(release.getFirst(), acknowledgement(release.getFirst(), "RELEASED"));

        assertThat(recordsHoldRegistryService.get(holdId).status()).isEqualTo(RecordsHoldStatus.RELEASED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT delivery_status FROM lifecycle_hold_instruction_outbox
                WHERE instruction_id = ?
                """, String.class, release.getFirst().instruction().instructionId()))
                .isEqualTo("ACKNOWLEDGED");
    }

    private void acknowledgeDueHoldInstructions() {
        var due = holdInstructionOutbox.claimDue(Instant.now().plusSeconds(1),
                Instant.now().plusSeconds(31), 20);
        for (var instruction : due) {
            holdAcknowledgements.accept(instruction,
                    acknowledgement(instruction, "APPLY".equals(instruction.instruction().operation())
                            ? "ACTIVE" : "RELEASED"));
        }
    }

    private static BookingHoldAcknowledgement acknowledgement(
            rw.rra.roomiq.lifecycle.holdcontrol.RecordsHoldInstruction instruction, String status) {
        return new BookingHoldAcknowledgement(instruction.instruction().instructionId(),
                instruction.instruction().holdId(), instruction.instruction().instructionRevision(),
                BookingHoldAcknowledgement.Outcome.ACCEPTED, status, 1L);
    }

    @Test
    void holdStateAndLifecycleEventRollBackTogether() {
        UUID actorId = UUID.randomUUID();
        UUID holdId = UUID.randomUUID();
        String recordId = UUID.randomUUID().toString();
        CreateRecordsHoldRequest request = new CreateRecordsHoldRequest(
                holdId, "booking-service", "BOOKING_REQUEST", recordId, "TEST_INSTRUCTION-ROLLBACK");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            recordsHoldRegistryService.create(request, actorId, "hold-rollback", "hold-rollback-correlation");
            throw new IllegalStateException("force transaction rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force transaction rollback");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM lifecycle_legal_hold WHERE hold_id = ?
                """, Integer.class, holdId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM lifecycle_control_event
                WHERE aggregate_type = 'HOLD' AND aggregate_id = ?
                """, Integer.class, holdId)).isZero();
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
                  "effectiveTo": "%s",
                  "approvingAuthorityRef": "SYNTHETIC_TEST_ONLY-authority-%s",
                  "legalValidationRef": "SYNTHETIC_TEST_ONLY-legal-%s",
                  "recordsValidationRef": "SYNTHETIC_TEST_ONLY-records-%s"
                }
                """.formatted(policyId, policyId, effectiveFrom, effectiveFrom.plus(1, ChronoUnit.HOURS),
                policyId, policyId, policyId);
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
