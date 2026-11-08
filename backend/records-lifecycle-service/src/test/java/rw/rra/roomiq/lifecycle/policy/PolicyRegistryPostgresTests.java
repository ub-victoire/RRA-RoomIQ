package rw.rra.roomiq.lifecycle.policy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import rw.rra.roomiq.lifecycle.retention.HoldStatus;
import rw.rra.roomiq.lifecycle.retention.RetentionEvaluationEvidence;
import rw.rra.roomiq.lifecycle.retention.RetentionEvaluationResult;
import rw.rra.roomiq.lifecycle.retention.RetentionPolicyEvaluationService;
import rw.rra.roomiq.lifecycle.security.IdentityRetentionPolicyAuthorizationClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PolicyRegistryPostgresTests {
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
    private RetentionPolicyEvaluationService evaluationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LifecyclePolicyVersionRepository versionRepository;

    @MockitoBean
    private IdentityRetentionPolicyAuthorizationClient authorizationClient;

    @Test
    void migrationCreatesApprovedPolicyVersionAndRuleSchema() {
        assertThat(dbaJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1', '2', '3', '4') AND success = TRUE",
                Integer.class)).isEqualTo(4);
        assertThat(dbaJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '5' AND success = TRUE",
                Integer.class)).isEqualTo(1);

        assertThat(columns("lifecycle_policy_version")).containsExactlyInAnyOrder(
                "policy_version_id", "version_label", "document_sha256", "approval_reference", "policy_version",
                "effective_from", "effective_to", "approving_authority_ref", "approved_by_subject", "approved_at",
                "legal_validation_ref", "records_validation_ref", "status", "created_at");
        assertThat(columns("lifecycle_policy_rule")).containsExactlyInAnyOrder(
                "rule_id", "policy_version_id", "rule_code", "category_code", "rule_source", "duration_value",
                "duration_unit", "start_basis_code", "schedule_reference", "created_at");
        assertThat(constraints("lifecycle_policy_version"))
                .contains("ck_lifecycle_policy_version_status", "ck_lifecycle_policy_version_effective_interval",
                        "ck_lifecycle_policy_version_approval");
        assertThat(constraints("lifecycle_policy_rule"))
                .contains("fk_lifecycle_policy_rule_version", "ck_lifecycle_policy_rule_source",
                        "ck_lifecycle_policy_rule_duration", "ck_lifecycle_policy_rule_statutory_reference",
                        "uq_lifecycle_policy_rule_code");
        assertThat(indexExists("ix_lifecycle_policy_version_effective")).isTrue();
        assertThat(indexExists("ix_lifecycle_policy_rule_category")).isTrue();
        assertThat(columns("lifecycle_control_event")).containsExactlyInAnyOrder(
                "event_id", "aggregate_type", "aggregate_id", "sequence_no", "event_type", "actor_subject",
                "recorded_at", "evidence_sha256", "metadata");
        assertThat(constraints("lifecycle_control_event"))
                .contains("ck_lifecycle_control_event_aggregate_type", "ck_lifecycle_control_event_sequence",
                        "uq_lifecycle_control_event_sequence", "ck_lifecycle_control_event_type");
        assertThat(indexExists("ix_lifecycle_control_event_aggregate_recorded")).isTrue();
    }

    @Test
    void runtimeRoleIsNotOwnerAndHasOnlyAuditedPolicyWritePrivileges() {
        assertThat(dbaJdbcTemplate().queryForObject("""
                SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls
                FROM pg_roles WHERE rolname = 'roomiq_rlc_app'
                """, Boolean.class)).isFalse();
        assertThat(dbaJdbcTemplate().queryForObject("""
                SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls
                FROM pg_roles WHERE rolname = 'roomiq_rlc_migrator'
                """, Boolean.class)).isFalse();
        assertThat(dbaJdbcTemplate().queryForObject("""
                SELECT pg_has_role('roomiq_rlc_app', 'roomiq_rlc_migrator', 'MEMBER')
                """, Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_userbyid(relowner)
                FROM pg_class
                WHERE oid = 'public.lifecycle_policy_version'::regclass
                """, String.class)).isEqualTo("roomiq_rlc_migrator");
        assertThat(privilege("lifecycle_policy_version", "SELECT")).isTrue();
        assertThat(privilege("lifecycle_policy_version", "INSERT")).isTrue();
        assertThat(privilege("lifecycle_policy_version", "UPDATE")).isTrue();
        assertThat(privilege("lifecycle_policy_version", "DELETE")).isFalse();
        assertThat(privilege("lifecycle_policy_version", "TRUNCATE")).isFalse();
        assertThat(privilege("lifecycle_policy_rule", "DELETE")).isFalse();
        assertThat(privilege("lifecycle_policy_rule", "TRUNCATE")).isFalse();
        assertThat(privilege("lifecycle_control_event", "SELECT")).isTrue();
        assertThat(privilege("lifecycle_control_event", "INSERT")).isTrue();
        assertThat(privilege("lifecycle_control_event", "UPDATE")).isFalse();
        assertThat(privilege("lifecycle_control_event", "DELETE")).isFalse();
        assertThat(privilege("lifecycle_control_event", "TRUNCATE")).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_schema_privilege(current_user, 'public', 'CREATE')", Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_schema_privilege(current_user, 'public', 'USAGE')", Boolean.class)).isTrue();
    }

    @Test
    void databaseRejectsPolicyWritesWithoutMatchingLifecycleEvent() {
        int policyVersionsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_policy_version", Integer.class);
        int policyRulesBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_policy_rule", Integer.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO lifecycle_policy_version
                    (policy_version_id, version_label, document_sha256, status, created_at)
                VALUES (?, ?, ?, 'DRAFT', ?)
                """, UUID.randomUUID(), "draft-" + UUID.randomUUID(), "a".repeat(64),
                Timestamp.from(Instant.now()))).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO lifecycle_policy_rule
                    (rule_id, policy_version_id, rule_code, category_code, rule_source, duration_value,
                     duration_unit, start_basis_code, created_at)
                VALUES (?, ?, 'ACCESS', 'ACCESS_AUTHENTICATION', 'RRA_PROPOSED', 2, 'YEAR',
                        'ORIGINAL_EVENT', ?)
                """, UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(Instant.now())))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_policy_version", Integer.class)).isEqualTo(policyVersionsBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_policy_rule", Integer.class)).isEqualTo(policyRulesBefore);
        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE lifecycle_policy_version CASCADE"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE lifecycle_policy_rule CASCADE"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void runtimeCannotUpdatePolicyWithoutLifecycleEvent() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        createPolicy(actorId, policyId, UUID.randomUUID(), "guard-" + policyId, "RRA_PROPOSED");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE lifecycle_policy_version
                SET version_label = ?
                WHERE policy_version_id = ?
                """, "untracked-" + policyId, policyId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM lifecycle_policy_version WHERE policy_version_id = ?", policyId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE lifecycle_policy_version"))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT version_label FROM lifecycle_policy_version WHERE policy_version_id = ?
                """, String.class, policyId)).isEqualTo("guard-" + policyId);
    }

    @Test
    void policyWriteRollsBackWhenPersistenceFailsAfterEventInsert() throws Exception {
        String triggerFunction = "rlc_test_reject_policy_insert";
        String triggerName = "rlc_test_reject_policy_insert_trigger";
        JdbcTemplate dba = dbaJdbcTemplate();
        dba.execute("""
                CREATE FUNCTION public.rlc_test_reject_policy_insert()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test policy persistence failure';
                END;
                $$
                """);
        dba.execute("""
                CREATE TRIGGER rlc_test_reject_policy_insert_trigger
                BEFORE INSERT ON lifecycle_policy_version
                FOR EACH ROW EXECUTE FUNCTION public.rlc_test_reject_policy_insert()
                """);

        UUID actorId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        when(authorizationClient.authorize(any(), any(), any())).thenReturn(actorId);
        String body = """
                {
                  "policyVersionId": "%s",
                  "versionLabel": "rollback-%s",
                  "documentSha256": "%s",
                  "rules": [{
                    "ruleId": "%s",
                    "ruleCode": "ACCESS",
                    "categoryCode": "ACCESS_AUTHENTICATION",
                    "ruleSource": "RRA_PROPOSED",
                    "durationValue": 2,
                    "durationUnit": "YEAR",
                    "startBasisCode": "ORIGINAL_EVENT"
                  }]
                }
                """.formatted(policyId, policyId, "a".repeat(64), ruleId);

        try {
            mockMvc.perform(post("/api/v1/retention/policies")
                            .with(jwt().jwt(token -> token.subject(actorId.toString())))
                            .header("Idempotency-Key", "rollback-" + policyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isInternalServerError());
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM lifecycle_policy_version WHERE policy_version_id = ?
                    """, Integer.class, policyId)).isZero();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM lifecycle_control_event WHERE aggregate_id = ?
                    """, Integer.class, policyId)).isZero();
        } finally {
            dba.execute("DROP TRIGGER " + triggerName + " ON lifecycle_policy_version");
            dba.execute("DROP FUNCTION public." + triggerFunction + "()");
        }
    }

    @Test
    void controlEventsAreAppendOnlyAndSupportTheApprovedSchema() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO lifecycle_control_event
                    (event_id, aggregate_type, aggregate_id, sequence_no, event_type, actor_subject, recorded_at)
                VALUES (?, 'POLICY', ?, 1, 'POLICY_VALIDATED', 'test-subject', ?)
                """, eventId, aggregateId, Timestamp.from(Instant.now()));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT metadata::text FROM lifecycle_control_event WHERE event_id = ?",
                String.class, eventId)).isEqualTo("{}");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE lifecycle_control_event SET actor_subject = 'changed' WHERE event_id = ?
                """, eventId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM lifecycle_control_event WHERE event_id = ?", eventId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE lifecycle_control_event"))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_control_event WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
    }

    @Test
    void policyCreateEndpointUsesIdentityActorAndRetriesWithoutDuplicateEvents() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        when(authorizationClient.authorize(any(), any(), any())).thenReturn(actorId);
        String body = """
                {
                  "policyVersionId": "%s",
                  "versionLabel": "draft-%s",
                  "documentSha256": "%s",
                  "rules": [{
                    "ruleId": "%s",
                    "ruleCode": "ACCESS",
                    "categoryCode": "ACCESS_AUTHENTICATION",
                    "ruleSource": "RRA_PROPOSED",
                    "durationValue": 2,
                    "durationUnit": "YEAR",
                    "startBasisCode": "ORIGINAL_EVENT"
                  }]
                }
                """.formatted(policyId, policyId, "a".repeat(64), ruleId);
        var request = post("/api/v1/retention/policies")
                .with(jwt().jwt(token -> token.subject(actorId.toString())))
                .header("Idempotency-Key", "policy-create-" + policyId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);

        mockMvc.perform(request).andExpect(status().isCreated());
        mockMvc.perform(request).andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_policy_version WHERE policy_version_id = ?",
                Integer.class, policyId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_policy_rule WHERE policy_version_id = ?",
                Integer.class, policyId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_control_event WHERE aggregate_id = ?",
                Integer.class, policyId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM lifecycle_control_event
                WHERE aggregate_id = ? AND actor_subject = ?
                """, Integer.class, policyId, actorId.toString())).isEqualTo(2);
    }

    @Test
    void identityDenialPreventsPolicyManagement() throws Exception {
        UUID actorId = UUID.randomUUID();
        int policyVersionsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_policy_version", Integer.class);
        when(authorizationClient.authorize(any(), any(), any()))
                .thenThrow(new rw.rra.roomiq.common.web.DomainException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "ACCESS_DENIED", "denied"));

        mockMvc.perform(post("/api/v1/retention/policies")
                        .with(jwt().jwt(token -> token.subject(actorId.toString())))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_policy_version", Integer.class)).isEqualTo(policyVersionsBefore);
    }

    @Test
    void identityUnavailableFailsClosedWithoutWritingPolicyOrLifecycleEvidence() throws Exception {
        UUID actorId = UUID.randomUUID();
        int policyVersionsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_policy_version", Integer.class);
        int eventsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_control_event", Integer.class);
        when(authorizationClient.authorize(any(), any(), any()))
                .thenThrow(new rw.rra.roomiq.common.web.DomainException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "IDENTITY_AUTHORIZATION_UNAVAILABLE", "Identity unavailable"));

        mockMvc.perform(post("/api/v1/retention/policies")
                        .with(jwt().jwt(token -> token.subject(actorId.toString())))
                        .header("X-Correlation-ID", "rlc-identity-unavailable")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Correlation-ID", "rlc-identity-unavailable"));
        verify(authorizationClient).authorize(isNull(), eq(actorId), eq("rlc-identity-unavailable"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_policy_version", Integer.class)).isEqualTo(policyVersionsBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_control_event", Integer.class)).isEqualTo(eventsBefore);
    }

    @Test
    void readinessEndpointIsPublicAndIncludesDatabaseState() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/readyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void policyActivationAndSupersessionAreAtomicAndPreserveHistoricalSelection() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID firstPolicyId = UUID.randomUUID();
        UUID secondPolicyId = UUID.randomUUID();
        Instant firstEffectiveFrom = Instant.now().plusSeconds(1200);
        Instant boundary = Instant.now().plusSeconds(3600);

        createPolicy(actorId, firstPolicyId, UUID.randomUUID(), "first-" + firstPolicyId, "RRA_APPROVED");
        mockMvc.perform(post("/api/v1/retention/policies/{policyId}/validate", firstPolicyId)
                        .with(jwt().jwt(token -> token.subject(actorId.toString())))
                        .header("Idempotency-Key", "validate-" + firstPolicyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        activatePolicy(actorId, firstPolicyId, "activate-" + firstPolicyId,
                firstEffectiveFrom, null);
        int activatedPolicyEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_control_event WHERE aggregate_id = ?",
                Integer.class, firstPolicyId);
        activatePolicy(actorId, firstPolicyId, "activate-" + firstPolicyId,
                firstEffectiveFrom, null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_control_event WHERE aggregate_id = ?",
                Integer.class, firstPolicyId)).isEqualTo(activatedPolicyEvents);

        mockMvc.perform(post("/api/v1/retention/policies/{policyId}/supersede", firstPolicyId)
                        .with(jwt().jwt(token -> token.subject(actorId.toString())))
                        .header("Idempotency-Key", "supersede-" + firstPolicyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveTo\":\"" + boundary + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPERSEDED"));
        int firstPolicyEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_control_event WHERE aggregate_id = ?",
                Integer.class, firstPolicyId);
        mockMvc.perform(post("/api/v1/retention/policies/{policyId}/supersede", firstPolicyId)
                        .with(jwt().jwt(token -> token.subject(actorId.toString())))
                        .header("Idempotency-Key", "supersede-" + firstPolicyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveTo\":\"" + boundary + "\"}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_control_event WHERE aggregate_id = ?",
                Integer.class, firstPolicyId)).isEqualTo(firstPolicyEvents);

        createPolicy(actorId, secondPolicyId, UUID.randomUUID(), "second-" + secondPolicyId, "RRA_APPROVED");
        activatePolicy(actorId, secondPolicyId, "activate-" + secondPolicyId, boundary, null);

        assertThat(versionRepository.findEffectiveAt(firstEffectiveFrom.plusSeconds(600)))
                .extracting(LifecyclePolicyVersion::getPolicyVersionId)
                .containsExactly(firstPolicyId);
        assertThat(versionRepository.findEffectiveAt(boundary))
                .extracting(LifecyclePolicyVersion::getPolicyVersionId)
                .containsExactly(secondPolicyId);
        assertThat(jdbcTemplate.queryForList("""
                SELECT event_type FROM lifecycle_control_event
                WHERE aggregate_id = ? ORDER BY sequence_no
                """, String.class, firstPolicyId))
                .contains("POLICY_CREATED", "POLICY_RULE_CREATED", "POLICY_VALIDATED",
                        "POLICY_ACTIVATED", "POLICY_SUPERSEDED");
    }

    @Test
    void failedPolicyInsertRollsBackItsLifecycleEvent() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID existingPolicyId = UUID.randomUUID();
        UUID rejectedPolicyId = UUID.randomUUID();
        String conflictingLabel = "conflicting-" + UUID.randomUUID();
        createPolicy(actorId, existingPolicyId, UUID.randomUUID(), conflictingLabel, "RRA_APPROVED");

        String body = """
                {
                  "policyVersionId": "%s",
                  "versionLabel": "%s",
                  "documentSha256": "%s",
                  "rules": [{
                    "ruleId": "%s",
                    "ruleCode": "ACCESS",
                    "categoryCode": "ACCESS_AUTHENTICATION",
                    "ruleSource": "RRA_APPROVED",
                    "durationValue": 2,
                    "durationUnit": "YEAR",
                    "startBasisCode": "ORIGINAL_EVENT"
                  }]
                }
                """.formatted(rejectedPolicyId, conflictingLabel, "a".repeat(64), UUID.randomUUID());

        mockMvc.perform(post("/api/v1/retention/policies")
                        .with(jwt().jwt(token -> token.subject(actorId.toString())))
                        .header("Idempotency-Key", "rollback-" + rejectedPolicyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isInternalServerError());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_policy_version WHERE policy_version_id = ?",
                Integer.class, rejectedPolicyId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_policy_rule WHERE policy_version_id = ?",
                Integer.class, rejectedPolicyId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_control_event WHERE aggregate_id = ?",
                Integer.class, rejectedPolicyId)).isZero();
    }

    @Test
    void evaluationServiceDoesNotTreatMissingPolicyAsApplicable() {
        RetentionEvaluationResult result = evaluationService.evaluate(new RetentionEvaluationEvidence(
                true, true, "ACCESS_AUTHENTICATION", Instant.parse("2021-01-01T00:00:00Z"),
                Instant.parse("2040-01-01T00:00:00Z"), Map.of("ORIGINAL_EVENT", Instant.parse("2020-01-01T00:00:00Z")),
                Map.of(), true, List.of(HoldStatus.RELEASED)));

        assertThat(result.reason()).isEqualTo(RetentionEvaluationResult.Reason.NO_APPLICABLE_POLICY);
    }

    private void createPolicy(UUID actorId, UUID policyId, UUID ruleId, String versionLabel, String ruleSource)
            throws Exception {
        when(authorizationClient.authorize(any(), any(), any())).thenReturn(actorId);
        String body = """
                {
                  "policyVersionId": "%s",
                  "versionLabel": "%s",
                  "documentSha256": "%s",
                  "rules": [{
                    "ruleId": "%s",
                    "ruleCode": "ACCESS",
                    "categoryCode": "ACCESS_AUTHENTICATION",
                    "ruleSource": "%s",
                    "durationValue": 2,
                    "durationUnit": "YEAR",
                    "startBasisCode": "ORIGINAL_EVENT"
                  }]
                }
                """.formatted(policyId, versionLabel, "a".repeat(64), ruleId, ruleSource);
        mockMvc.perform(post("/api/v1/retention/policies")
                        .with(jwt().jwt(token -> token.subject(actorId.toString())))
                        .header("Idempotency-Key", "create-" + policyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private void activatePolicy(UUID actorId, UUID policyId, String idempotencyKey,
                                Instant effectiveFrom, Instant effectiveTo) throws Exception {
        String effectiveToJson = effectiveTo == null ? "null" : "\"" + effectiveTo + "\"";
        String body = """
                {
                  "approvalReference": "approval-%s",
                  "policyVersion": "version-%s",
                  "effectiveFrom": "%s",
                  "effectiveTo": %s,
                  "approvingAuthorityRef": "authority-%s",
                  "legalValidationRef": "legal-%s",
                  "recordsValidationRef": "records-%s"
                }
                """.formatted(policyId, policyId, effectiveFrom, effectiveToJson,
                policyId, policyId, policyId);
        mockMvc.perform(post("/api/v1/retention/policies/{policyId}/activate", policyId)
                        .with(jwt().jwt(token -> token.subject(actorId.toString())))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private List<String> columns(String tableName) {
        return jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """, String.class, tableName);
    }

    private List<String> constraints(String tableName) {
        return jdbcTemplate.queryForList("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public' AND table_name = ?
                """, String.class, tableName);
    }

    private boolean indexExists(String indexName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                Integer.class, indexName) == 1;
    }

    private boolean privilege(String tableName, String privilege) {
        return jdbcTemplate.queryForObject(
                "SELECT has_table_privilege(current_user, ?, ?)", Boolean.class,
                "public." + tableName, privilege);
    }

    private JdbcTemplate dbaJdbcTemplate() {
        return new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }
}
