package rw.rra.roomiq.lifecycle.sourceevidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SourceOwnerEvidenceRepository {
    private final JdbcTemplate jdbcTemplate;

    public SourceOwnerEvidenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockRecord(String sourceService, String recordType, String recordId) {
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 396201))")) {
                statement.setString(1, sourceService + "\n" + recordType + "\n" + recordId);
                statement.execute();
            }
            return null;
        });
    }

    public List<SourceOwnerEvidenceReceipt> findBySourceEventId(String sourceService, UUID eventId) {
        return jdbcTemplate.query("""
                SELECT * FROM lifecycle_source_evidence_receipt
                WHERE source_service = ? AND event_id = ?
                ORDER BY receipt_id
                """, this::mapReceipt, sourceService, eventId);
    }

    public Optional<SourceOwnerEvidenceReceipt> findLatestAccepted(
            String sourceService, String recordType, String recordId) {
        return jdbcTemplate.query("""
                SELECT * FROM lifecycle_source_evidence_receipt
                WHERE source_service = ? AND record_type = ? AND record_id = ?
                  AND outcome = 'ACCEPTED'
                ORDER BY source_revision DESC, receipt_id DESC
                LIMIT 1
                """, this::mapReceipt, sourceService, recordType, recordId).stream().findFirst();
    }

    public Optional<Long> findLatestConflictRevision(String sourceService, String recordType, String recordId) {
        return jdbcTemplate.query("""
                SELECT MAX(source_revision) FROM lifecycle_source_evidence_receipt
                WHERE source_service = ? AND record_type = ? AND record_id = ?
                  AND outcome = 'CONFLICTED'
                """, resultSet -> resultSet.next() && resultSet.getObject(1) != null
                        ? Optional.of(resultSet.getLong(1)) : Optional.empty(),
                sourceService, recordType, recordId);
    }

    public SourceOwnerEvidenceReceipt append(SourceOwnerEvidenceReceipt receipt) {
        Long receiptId = jdbcTemplate.queryForObject("""
                INSERT INTO lifecycle_source_evidence_receipt (
                    contract_version, source_service, authenticated_source_service, record_type, record_id,
                    event_id, event_type, event_version, source_revision, category_code, record_created_at,
                    occurred_at, authoritative_start_at, start_basis_code, closed_at, decision_at,
                    transaction_closed_at, actor_subject, correlation_id, causation_id, supersedes_event_id,
                    received_at, evidence_sha256, outcome, rejection_code, current_lifecycle_status, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING receipt_id
                """, Long.class,
                receipt.evidence().contractVersion(), receipt.evidence().sourceService(),
                receipt.authenticatedSourceService(), receipt.evidence().recordType(), receipt.evidence().recordId(),
                receipt.evidence().eventId(), receipt.evidence().eventType(), receipt.evidence().eventVersion(),
                receipt.evidence().sourceRevision(), receipt.evidence().categoryCode(),
                Timestamp.from(receipt.evidence().recordCreatedAt()), Timestamp.from(receipt.evidence().occurredAt()),
                Timestamp.from(receipt.evidence().authoritativeStartAt()), receipt.evidence().startBasisCode(),
                toTimestamp(receipt.evidence().closedAt()), toTimestamp(receipt.evidence().decisionAt()),
                toTimestamp(receipt.evidence().transactionClosedAt()), receipt.evidence().actorSubject(),
                receipt.evidence().correlationId(), receipt.evidence().causationId(),
                receipt.evidence().supersedesEventId(), Timestamp.from(receipt.receivedAt()),
                receipt.evidenceSha256(), receipt.outcome().name(), receipt.rejectionCode(),
                receipt.evidence().currentLifecycleStatus(), Timestamp.from(receipt.evidence().observedAt()));
        return new SourceOwnerEvidenceReceipt(receiptId, receipt.evidence(), receipt.authenticatedSourceService(),
                receipt.receivedAt(), receipt.evidenceSha256(), receipt.outcome(), receipt.rejectionCode());
    }

    private SourceOwnerEvidenceReceipt mapReceipt(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        SourceOwnerEvidence evidence = new SourceOwnerEvidence(
                resultSet.getInt("contract_version"),
                resultSet.getString("source_service"),
                resultSet.getString("record_type"),
                resultSet.getString("record_id"),
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getInt("event_version"),
                resultSet.getLong("source_revision"),
                resultSet.getString("category_code"),
                resultSet.getTimestamp("record_created_at").toInstant(),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getTimestamp("authoritative_start_at").toInstant(),
                resultSet.getString("start_basis_code"),
                toInstant(resultSet.getTimestamp("closed_at")),
                toInstant(resultSet.getTimestamp("decision_at")),
                toInstant(resultSet.getTimestamp("transaction_closed_at")),
                resultSet.getString("actor_subject"),
                resultSet.getString("correlation_id"),
                resultSet.getString("causation_id"),
                resultSet.getObject("supersedes_event_id", UUID.class),
                resultSet.getString("current_lifecycle_status"),
                resultSet.getTimestamp("observed_at") == null
                        ? null : resultSet.getTimestamp("observed_at").toInstant());
        return new SourceOwnerEvidenceReceipt(
                resultSet.getLong("receipt_id"),
                evidence,
                resultSet.getString("authenticated_source_service"),
                resultSet.getTimestamp("received_at").toInstant(),
                resultSet.getString("evidence_sha256"),
                SourceOwnerEvidenceOutcome.valueOf(resultSet.getString("outcome")),
                resultSet.getString("rejection_code"));
    }

    private static Timestamp toTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
