package rw.rra.roomiq.lifecycle.holdcontrol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import rw.rra.roomiq.lifecycle.retention.HoldStatus;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RecordsHoldRepository {
    private final JdbcTemplate jdbcTemplate;

    public RecordsHoldRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RecordsLegalHold> findById(UUID holdId) {
        return jdbcTemplate.query("SELECT * FROM lifecycle_legal_hold WHERE hold_id = ?",
                RecordsHoldRepository::map, holdId).stream().findFirst();
    }

    public Optional<RecordsLegalHold> findByIdForUpdate(UUID holdId) {
        return jdbcTemplate.query("SELECT * FROM lifecycle_legal_hold WHERE hold_id = ? FOR UPDATE",
                RecordsHoldRepository::map, holdId).stream().findFirst();
    }

    public List<HoldStatus> findStatuses(String sourceService, String recordType, String recordId) {
        return findHolds(sourceService, recordType, recordId).stream()
                .map(hold -> HoldStatus.valueOf(hold.status().name())).toList();
    }

    public List<RecordsLegalHold> findHolds(String sourceService, String recordType, String recordId) {
        return jdbcTemplate.query("""
                SELECT * FROM lifecycle_legal_hold
                WHERE source_service = ? AND record_type = ? AND record_id = ?
                ORDER BY created_at, hold_id
                """, RecordsHoldRepository::map, sourceService, recordType, recordId);
    }

    public boolean hasBlockingHold(String sourceService, String recordType, String recordId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM lifecycle_legal_hold
                    WHERE source_service = ? AND record_type = ? AND record_id = ?
                      AND status <> 'RELEASED'
                )
                """, Boolean.class, sourceService, recordType, recordId);
        return Boolean.TRUE.equals(exists);
    }

    public void insert(RecordsLegalHold hold) {
        jdbcTemplate.update("""
                INSERT INTO lifecycle_legal_hold (
                    hold_id, source_service, record_type, record_id, instruction_reference,
                    status, created_by_user_id, created_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, hold.holdId(), hold.sourceService(), hold.recordType(), hold.recordId(),
                hold.instructionReference(), hold.status().name(), hold.createdByUserId(),
                Timestamp.from(hold.createdAt()), hold.version());
    }

    public void requestRelease(RecordsLegalHold hold, UUID actorId, Instant requestedAt, String reference) {
        int updated = jdbcTemplate.update("""
                UPDATE lifecycle_legal_hold
                SET status = 'RELEASE_PENDING', release_requested_by_user_id = ?,
                    release_requested_at = ?, release_reference = ?, version = version + 1
                WHERE hold_id = ? AND version = ?
                """, actorId, Timestamp.from(requestedAt), reference, hold.holdId(), hold.version());
        requireOneRow(updated);
    }

    public void verifyRelease(RecordsLegalHold hold, UUID actorId, Instant verifiedAt, String reference) {
        int updated = jdbcTemplate.update("""
                UPDATE lifecycle_legal_hold
                SET status = 'RELEASE_ENFORCEMENT_PENDING', verified_by_user_id = ?, verified_at = ?,
                    verification_reference = ?, version = version + 1
                WHERE hold_id = ? AND version = ?
                """, actorId, Timestamp.from(verifiedAt), reference, hold.holdId(), hold.version());
        requireOneRow(updated);
    }

    public void acknowledgeRelease(RecordsLegalHold hold) {
        int updated = jdbcTemplate.update("""
                UPDATE lifecycle_legal_hold
                SET status = 'RELEASED', version = version + 1
                WHERE hold_id = ? AND version = ?
                """, hold.holdId(), hold.version());
        requireOneRow(updated);
    }

    private static void requireOneRow(int updated) {
        if (updated != 1) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "Legal-hold state changed concurrently");
        }
    }

    private static RecordsLegalHold map(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new RecordsLegalHold(
                resultSet.getObject("hold_id", UUID.class),
                resultSet.getString("source_service"),
                resultSet.getString("record_type"),
                resultSet.getString("record_id"),
                resultSet.getString("instruction_reference"),
                RecordsHoldStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("created_by_user_id", UUID.class),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getObject("release_requested_by_user_id", UUID.class),
                toInstant(resultSet.getTimestamp("release_requested_at")),
                resultSet.getString("release_reference"),
                resultSet.getObject("verified_by_user_id", UUID.class),
                toInstant(resultSet.getTimestamp("verified_at")),
                resultSet.getString("verification_reference"),
                resultSet.getLong("version"));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
