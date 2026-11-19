package rw.rra.roomiq.audit.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    public List<AuditSourceEvidenceOutboxEntry> claimDue(
            Instant now, Instant leaseExpiresAt, int limit, UUID claimToken) {
        jdbcTemplate.update("""
                UPDATE audit_source_evidence_outbox
                SET attempt_count = attempt_count + 1,
                    delivery_status = CASE
                        WHEN attempt_count + 1 >= ? THEN 'RECONCILIATION_REQUIRED'
                        ELSE 'PENDING'
                    END,
                    next_attempt_at = CASE
                        WHEN attempt_count + 1 >= ? THEN next_attempt_at
                        ELSE CAST(? AS TIMESTAMP WITH TIME ZONE) + make_interval(
                            secs => LEAST(300, power(2, LEAST(attempt_count, 8)))::double precision)
                    END,
                    claim_token = NULL,
                    claim_expires_at = NULL,
                    last_error_code = 'RLC_DELIVERY_LEASE_EXPIRED'
                WHERE delivery_status = 'PENDING'
                  AND claim_token IS NOT NULL
                  AND claim_expires_at <= ?
                """, AuditSourceEvidenceOutboxTransactions.MAX_AUTOMATIC_ATTEMPTS,
                AuditSourceEvidenceOutboxTransactions.MAX_AUTOMATIC_ATTEMPTS,
                Timestamp.from(now), Timestamp.from(now));

        return jdbcTemplate.query("""
                WITH candidates AS (
                    SELECT audit_event_id
                    FROM audit_source_evidence_outbox
                    WHERE delivery_status = 'PENDING'
                      AND claim_token IS NULL
                      AND next_attempt_at <= ?
                    ORDER BY next_attempt_at, created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE audit_source_evidence_outbox AS outbox
                SET claim_token = ?, claim_expires_at = ?
                FROM candidates
                WHERE outbox.audit_event_id = candidates.audit_event_id
                RETURNING outbox.*
                """, (resultSet, rowNumber) -> mapEntry(resultSet),
                Timestamp.from(now), limit, claimToken, Timestamp.from(leaseExpiresAt));
    }

    public List<AuditSourceEvidenceOutboxEntry> configurationBlocked(int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM audit_source_evidence_outbox
                WHERE delivery_status = 'CONFIGURATION_BLOCKED'
                ORDER BY created_at
                LIMIT ?
                """, (resultSet, rowNumber) -> mapEntry(resultSet), limit);
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

    public boolean markDelivered(UUID eventId, UUID claimToken, Instant deliveredAt) {
        return jdbcTemplate.update("""
                UPDATE audit_source_evidence_outbox
                SET delivery_status = 'DELIVERED', delivered_at = ?, last_error_code = NULL,
                    claim_token = NULL, claim_expires_at = NULL
                WHERE audit_event_id = ? AND delivery_status = 'PENDING' AND claim_token = ?
                """, Timestamp.from(deliveredAt), eventId, claimToken) == 1;
    }

    public boolean defer(
            UUID eventId, UUID claimToken, Instant retryAt, String errorCode, boolean exhausted) {
        return jdbcTemplate.update("""
                UPDATE audit_source_evidence_outbox
                SET attempt_count = attempt_count + 1, next_attempt_at = ?, last_error_code = ?,
                    delivery_status = ?, claim_token = NULL, claim_expires_at = NULL
                WHERE audit_event_id = ? AND delivery_status = 'PENDING' AND claim_token = ?
                """, Timestamp.from(retryAt), errorCode,
                exhausted ? "RECONCILIATION_REQUIRED" : "PENDING", eventId, claimToken) == 1;
    }

    public boolean requireReconciliation(UUID eventId, UUID claimToken, String errorCode) {
        return jdbcTemplate.update("""
                UPDATE audit_source_evidence_outbox
                SET delivery_status = 'RECONCILIATION_REQUIRED', last_error_code = ?,
                    claim_token = NULL, claim_expires_at = NULL
                WHERE audit_event_id = ? AND delivery_status = 'PENDING' AND claim_token = ?
                """, errorCode, eventId, claimToken) == 1;
    }

    private static AuditSourceEvidenceOutboxEntry mapEntry(ResultSet resultSet) throws SQLException {
        return new AuditSourceEvidenceOutboxEntry(
                resultSet.getObject("audit_event_id", UUID.class),
                resultSet.getString("action"),
                resultSet.getString("category_code"),
                resultSet.getString("start_basis_code"),
                resultSet.getTimestamp("record_created_at").toInstant(),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getTimestamp("observed_at").toInstant(),
                resultSet.getString("correlation_id"),
                resultSet.getInt("attempt_count"),
                resultSet.getObject("claim_token", UUID.class));
    }
}
