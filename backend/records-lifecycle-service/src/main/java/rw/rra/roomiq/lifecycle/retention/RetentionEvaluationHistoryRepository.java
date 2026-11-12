package rw.rra.roomiq.lifecycle.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import rw.rra.roomiq.lifecycle.sourceevidence.SourceOwnerEvidenceReceipt;

import java.sql.Timestamp;
import java.util.UUID;

@Repository
public class RetentionEvaluationHistoryRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RetentionEvaluationHistoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID append(RetentionEvaluationHistoryProvenance provenance, SourceOwnerEvidenceReceipt receipt) {
        UUID evaluationId = UUID.randomUUID();
        String serializedProvenance;
        try {
            serializedProvenance = objectMapper.writeValueAsString(provenance);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Retention evaluation provenance could not be serialized", exception);
        }

        int inserted = jdbcTemplate.update("""
                INSERT INTO lifecycle_retention_evaluation (
                    evaluation_id, recorded_at, source_service, record_type, record_id,
                    source_evidence_receipt_id, source_event_id, source_revision, evidence_sha256,
                    evidence_received_at, category_code, authoritative_start_at, evaluation_at,
                    policy_version_id, evaluation_status, evaluation_reason, retain_until, provenance)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                """,
                evaluationId,
                timestamp(provenance.recordedAt()),
                provenance.sourceService(),
                provenance.recordType(),
                provenance.recordId(),
                receipt == null ? null : receipt.receiptId(),
                receipt == null ? null : receipt.evidence().eventId(),
                receipt == null ? null : receipt.evidence().sourceRevision(),
                provenance.sourceEvidenceSha256(),
                timestamp(provenance.evidenceReceivedAt()),
                receipt == null ? null : receipt.evidence().categoryCode(),
                receipt == null ? null : timestamp(receipt.evidence().authoritativeStartAt()),
                timestamp(provenance.evaluationAt()),
                provenance.evaluation().policyVersionId(),
                provenance.evaluation().status().name(),
                provenance.evaluation().reason().name(),
                timestamp(provenance.evaluation().retainUntil()),
                serializedProvenance);
        if (inserted != 1) {
            throw new IllegalStateException("Retention evaluation history was not durably recorded");
        }
        return evaluationId;
    }

    private static Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
