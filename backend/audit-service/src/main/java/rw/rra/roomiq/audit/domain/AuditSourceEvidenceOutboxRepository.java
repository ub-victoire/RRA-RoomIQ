package rw.rra.roomiq.audit.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class AuditSourceEvidenceOutboxRepository {
    private final JdbcTemplate jdbcTemplate;

    public AuditSourceEvidenceOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(AuditEvent event, String categoryCode, String startBasisCode,
                       Instant observedAt, String deliveryStatus, String errorCode) {
        jdbcTemplate.update("""
                INSERT INTO audit_source_evidence_outbox (
                    audit_event_id, action, category_code, start_basis_code, record_created_at,
                    occurred_at, observed_at, correlation_id, delivery_status, next_attempt_at,
                    attempt_count, last_error_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """, event.getId(), event.getAction(), categoryCode, startBasisCode,
                Timestamp.from(event.getIngestedAt()), Timestamp.from(event.getOccurredAt()),
                Timestamp.from(observedAt), event.getCorrelationId(), deliveryStatus,
                Timestamp.from(observedAt), errorCode);
    }

    public List<AuditSourceEvidenceOutboxEntry> claimDue(Instant now, int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM audit_source_evidence_outbox
                WHERE delivery_status = 'PENDING' AND next_attempt_at <= ?
                ORDER BY next_attempt_at, created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (resultSet, rowNumber) -> new AuditSourceEvidenceOutboxEntry(
                resultSet.getObject("audit_event_id", UUID.class),
                resultSet.getString("action"),
                resultSet.getString("category_code"),
                resultSet.getString("start_basis_code"),
                resultSet.getTimestamp("record_created_at").toInstant(),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getTimestamp("observed_at").toInstant(),
                resultSet.getString("correlation_id"),
                resultSet.getInt("attempt_count")), Timestamp.from(now), limit);
    }

    public List<AuditSourceEvidenceOutboxEntry> configurationBlocked(int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM audit_source_evidence_outbox
                WHERE delivery_status = 'CONFIGURATION_BLOCKED'
                ORDER BY created_at
                LIMIT ?
                """, (resultSet, rowNumber) -> new AuditSourceEvidenceOutboxEntry(
                resultSet.getObject("audit_event_id", UUID.class),
                resultSet.getString("action"),
                resultSet.getString("category_code"),
                resultSet.getString("start_basis_code"),
                resultSet.getTimestamp("record_created_at").toInstant(),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getTimestamp("observed_at").toInstant(),
                resultSet.getString("correlation_id"),
                resultSet.getInt("attempt_count")), limit);
    }

    public void applyMapping(UUID eventId, String categoryCode, String startBasisCode) {
        jdbcTemplate.update("""
                UPDATE audit_source_evidence_outbox
                SET category_code = ?, start_basis_code = ?, delivery_status = 'PENDING',
                    next_attempt_at = CURRENT_TIMESTAMP, last_error_code = NULL
                WHERE audit_event_id = ? AND delivery_status = 'CONFIGURATION_BLOCKED'
                """, categoryCode, startBasisCode, eventId);
    }

    public boolean recordConfigurationBlock(UUID eventId, String diagnosticCode) {
        return jdbcTemplate.update("""
                UPDATE audit_source_evidence_outbox
                SET last_error_code = ?
                WHERE audit_event_id = ? AND delivery_status = 'CONFIGURATION_BLOCKED'
                  AND last_error_code IS DISTINCT FROM ?
                """, diagnosticCode, eventId, diagnosticCode) == 1;
    }

    public void markDelivered(UUID eventId, Instant deliveredAt) {
        jdbcTemplate.update("""
                UPDATE audit_source_evidence_outbox
                SET delivery_status = 'DELIVERED', delivered_at = ?, last_error_code = NULL
                WHERE audit_event_id = ? AND delivery_status = 'PENDING'
                """, Timestamp.from(deliveredAt), eventId);
    }

    public void defer(UUID eventId, Instant retryAt, String errorCode, boolean exhausted) {
        jdbcTemplate.update("""
                UPDATE audit_source_evidence_outbox
                SET attempt_count = attempt_count + 1, next_attempt_at = ?, last_error_code = ?,
                    delivery_status = ?
                WHERE audit_event_id = ? AND delivery_status = 'PENDING'
                """, Timestamp.from(retryAt), errorCode,
                exhausted ? "RECONCILIATION_REQUIRED" : "PENDING", eventId);
    }

    public void requireReconciliation(UUID eventId, String errorCode) {
        jdbcTemplate.update("""
                UPDATE audit_source_evidence_outbox
                SET delivery_status = 'RECONCILIATION_REQUIRED', last_error_code = ?
                WHERE audit_event_id = ? AND delivery_status = 'PENDING'
                """, errorCode, eventId);
    }
}
