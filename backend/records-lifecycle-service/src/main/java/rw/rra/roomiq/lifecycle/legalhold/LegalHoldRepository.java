package rw.rra.roomiq.lifecycle.legalhold;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import rw.rra.roomiq.lifecycle.retention.HoldStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LegalHoldRepository {
    private final JdbcTemplate jdbcTemplate;

    public LegalHoldRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<LegalHoldRecord> findById(UUID holdId) {
        return queryOne("SELECT * FROM lifecycle_legal_hold WHERE hold_id = ?", holdId);
    }

    public Optional<LegalHoldRecord> findForUpdate(UUID holdId) {
        return queryOne("SELECT * FROM lifecycle_legal_hold WHERE hold_id = ? FOR UPDATE", holdId);
    }

    public void insert(LegalHoldRecord hold) {
        jdbcTemplate.update("""
                INSERT INTO lifecycle_legal_hold (
                    hold_id, version, hold_reference, reason, scope_type, source_service, record_type,
                    record_id, category_code, status, initiating_actor, created_at, updated_by, updated_at,
                    effective_from, approved_by, approved_at, release_requested_by, release_requested_at,
                    release_request_evidence_reference, released_by, released_at, release_verification_reference,
                    supporting_evidence_reference, last_evidence_reference, correlation_id, last_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                hold.holdId(), hold.version(), hold.holdReference(), hold.reason(), hold.scope().type().name(),
                hold.scope().sourceService(), hold.scope().recordType(), hold.scope().recordId(),
                hold.scope().categoryCode(), hold.status().name(), hold.initiatingActor(), timestamp(hold.createdAt()),
                hold.updatedBy(), timestamp(hold.updatedAt()), timestamp(hold.effectiveFrom()), hold.approvedBy(),
                timestamp(hold.approvedAt()), hold.releaseRequestedBy(), timestamp(hold.releaseRequestedAt()),
                hold.releaseRequestEvidenceReference(), hold.releasedBy(), timestamp(hold.releasedAt()),
                hold.releaseVerificationReference(), hold.supportingEvidenceReference(),
                hold.lastEvidenceReference(), hold.correlationId(), hold.lastEventId());
    }

    public void update(LegalHoldRecord hold) {
        int updated = jdbcTemplate.update("""
                UPDATE lifecycle_legal_hold
                SET version = ?, reason = ?, scope_type = ?, source_service = ?, record_type = ?, record_id = ?,
                    category_code = ?, status = ?, updated_by = ?, updated_at = ?, effective_from = ?,
                    approved_by = ?, approved_at = ?, release_requested_by = ?, release_requested_at = ?,
                    release_request_evidence_reference = ?, released_by = ?, released_at = ?,
                    release_verification_reference = ?, supporting_evidence_reference = ?,
                    last_evidence_reference = ?, correlation_id = ?, last_event_id = ?
                WHERE hold_id = ? AND version = ?
                """,
                hold.version(), hold.reason(), hold.scope().type().name(), hold.scope().sourceService(),
                hold.scope().recordType(), hold.scope().recordId(), hold.scope().categoryCode(),
                hold.status().name(), hold.updatedBy(), timestamp(hold.updatedAt()),
                timestamp(hold.effectiveFrom()), hold.approvedBy(), timestamp(hold.approvedAt()),
                hold.releaseRequestedBy(), timestamp(hold.releaseRequestedAt()),
                hold.releaseRequestEvidenceReference(), hold.releasedBy(), timestamp(hold.releasedAt()),
                hold.releaseVerificationReference(), hold.supportingEvidenceReference(),
                hold.lastEvidenceReference(), hold.correlationId(), hold.lastEventId(),
                hold.holdId(), hold.version() - 1);
        if (updated != 1) {
            throw new IllegalStateException("Legal hold changed while its row lock was held");
        }
    }

    public LegalHoldEvaluationState findApplicable(String sourceService, String recordType, String recordId,
                                                    String categoryCode) {
        List<ApplicableLegalHold> holds = jdbcTemplate.query("""
                SELECT hold_id, hold_reference, version, scope_type, source_service, record_type, record_id,
                       category_code, status
                FROM lifecycle_legal_hold
                WHERE status IN ('PENDING_APPROVAL', 'ACTIVE', 'RELEASE_PENDING')
                  AND ((scope_type = 'RECORD' AND source_service = ? AND record_type = ? AND record_id = ?)
                    OR (scope_type = 'CATEGORY' AND category_code = ?))
                ORDER BY hold_reference, hold_id
                """, (rs, rowNum) -> mapApplicable(rs), sourceService, recordType, recordId, categoryCode);
        return new LegalHoldEvaluationState(true, holds);
    }

    private Optional<LegalHoldRecord> queryOne(String sql, UUID holdId) {
        List<LegalHoldRecord> rows = jdbcTemplate.query(sql, (rs, rowNum) -> map(rs), holdId);
        return rows.stream().findFirst();
    }

    private static LegalHoldRecord map(ResultSet rs) throws SQLException {
        LegalHoldScope scope = new LegalHoldScope(LegalHoldScopeType.valueOf(rs.getString("scope_type")),
                rs.getString("source_service"), rs.getString("record_type"), rs.getString("record_id"),
                rs.getString("category_code"));
        return new LegalHoldRecord(rs.getObject("hold_id", UUID.class), rs.getLong("version"),
                rs.getString("hold_reference"), rs.getString("reason"), scope,
                LegalHoldStatus.valueOf(rs.getString("status")),
                rs.getObject("initiating_actor", UUID.class), instant(rs, "created_at"),
                rs.getObject("updated_by", UUID.class), instant(rs, "updated_at"),
                instant(rs, "effective_from"), rs.getObject("approved_by", UUID.class),
                instant(rs, "approved_at"), rs.getObject("release_requested_by", UUID.class),
                instant(rs, "release_requested_at"), rs.getString("release_request_evidence_reference"),
                rs.getObject("released_by", UUID.class), instant(rs, "released_at"),
                rs.getString("release_verification_reference"), rs.getString("supporting_evidence_reference"),
                rs.getString("last_evidence_reference"), rs.getString("correlation_id"),
                rs.getObject("last_event_id", UUID.class));
    }

    private static ApplicableLegalHold mapApplicable(ResultSet rs) throws SQLException {
        LegalHoldStatus status = LegalHoldStatus.valueOf(rs.getString("status"));
        HoldStatus evaluationStatus = switch (status) {
            case PENDING_APPROVAL -> HoldStatus.PENDING_BLOCK;
            case ACTIVE -> HoldStatus.ACTIVE;
            case RELEASE_PENDING -> HoldStatus.RELEASE_PENDING;
            default -> throw new IllegalStateException("Unexpected status in applicable hold query");
        };
        LegalHoldScope scope = new LegalHoldScope(LegalHoldScopeType.valueOf(rs.getString("scope_type")),
                rs.getString("source_service"), rs.getString("record_type"), rs.getString("record_id"),
                rs.getString("category_code"));
        return new ApplicableLegalHold(rs.getObject("hold_id", UUID.class), rs.getString("hold_reference"),
                status, evaluationStatus, rs.getLong("version"), scope);
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
