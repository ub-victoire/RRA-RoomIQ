package rw.rra.roomiq.lifecycle.holdcontrol;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class RecordsHoldInstructionOutboxRepository {
    private final JdbcTemplate jdbcTemplate;

    public RecordsHoldInstructionOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void enqueue(RecordsLegalHold hold, long revision, String operation, String correlationId,
                        Instant issuedAt) {
        jdbcTemplate.update("""
                INSERT INTO lifecycle_hold_instruction_outbox (
                    outbox_id, instruction_id, hold_id, instruction_revision, operation,
                    source_service, record_type, record_id, instruction_reference_sha256,
                    issued_at, correlation_id, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), hold.holdId(), revision, operation,
                hold.sourceService(), hold.recordType(), hold.recordId(), sha256(hold.instructionReference()),
                Timestamp.from(issuedAt), correlationId, Timestamp.from(issuedAt));
    }

    public List<RecordsHoldInstruction> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbcTemplate.query("""
                WITH candidates AS (
                    SELECT current.outbox_id
                    FROM lifecycle_hold_instruction_outbox current
                    WHERE (
                        (current.delivery_status = 'PENDING' AND current.next_attempt_at <= ?)
                        OR (current.delivery_status = 'PROCESSING' AND current.lease_expires_at <= ?)
                    )
                    AND NOT EXISTS (
                        SELECT 1 FROM lifecycle_hold_instruction_outbox previous
                        WHERE previous.hold_id = current.hold_id
                          AND previous.instruction_revision < current.instruction_revision
                          AND previous.delivery_status <> 'ACKNOWLEDGED'
                    )
                    ORDER BY current.next_attempt_at, current.issued_at, current.outbox_id
                    LIMIT ?
                    FOR UPDATE OF current SKIP LOCKED
                )
                UPDATE lifecycle_hold_instruction_outbox outbox
                SET delivery_status = 'PROCESSING',
                    attempt_count = attempt_count + 1,
                    lease_expires_at = ?,
                    last_error_code = NULL
                FROM candidates
                WHERE outbox.outbox_id = candidates.outbox_id
                RETURNING outbox.*
                """, RecordsHoldInstruction::from,
                Timestamp.from(now), Timestamp.from(now), limit, Timestamp.from(leaseUntil));
    }

    public RecordsHoldInstruction findForUpdate(UUID outboxId) {
        return jdbcTemplate.query("""
                SELECT * FROM lifecycle_hold_instruction_outbox WHERE outbox_id = ? FOR UPDATE
                """, RecordsHoldInstruction::from, outboxId).stream().findFirst().orElseThrow();
    }

    public void acknowledge(RecordsHoldInstruction instruction, BookingHoldAcknowledgement acknowledgement,
                            Instant now) {
        String expectedState = "APPLY".equals(instruction.instruction().operation()) ? "ACTIVE" : "RELEASED";
        boolean accepted = acknowledgement != null
                && instruction.instruction().instructionId().equals(acknowledgement.instructionId())
                && instruction.instruction().holdId().equals(acknowledgement.holdId())
                && instruction.instruction().instructionRevision() == acknowledgement.instructionRevision()
                && (acknowledgement.outcome() == BookingHoldAcknowledgement.Outcome.ACCEPTED
                    || acknowledgement.outcome() == BookingHoldAcknowledgement.Outcome.DUPLICATE)
                && expectedState.equals(acknowledgement.enforcementStatus());
        if (accepted) {
            jdbcTemplate.update("""
                    UPDATE lifecycle_hold_instruction_outbox
                    SET delivery_status = 'ACKNOWLEDGED', last_acknowledgement_outcome = ?,
                        acknowledged_enforcement_status = ?, acknowledged_booking_request_version = ?,
                        acknowledged_at = ?, lease_expires_at = NULL
                    WHERE outbox_id = ? AND delivery_status = 'PROCESSING'
                    """, acknowledgement.outcome().name(), acknowledgement.enforcementStatus(),
                    acknowledgement.bookingRequestVersion(), Timestamp.from(now), instruction.outboxId());
        } else {
            jdbcTemplate.update("""
                    UPDATE lifecycle_hold_instruction_outbox
                    SET delivery_status = 'RECONCILIATION_REQUIRED',
                        last_acknowledgement_outcome = ?, acknowledged_enforcement_status = ?,
                        acknowledged_booking_request_version = ?, last_error_code = 'ACKNOWLEDGEMENT_NOT_ACCEPTED',
                        lease_expires_at = NULL
                    WHERE outbox_id = ? AND delivery_status = 'PROCESSING'
                    """, acknowledgement == null ? null : acknowledgement.outcome().name(),
                    acknowledgement == null ? null : acknowledgement.enforcementStatus(),
                    acknowledgement == null ? null : acknowledgement.bookingRequestVersion(),
                    instruction.outboxId());
        }
    }

    public void retry(RecordsHoldInstruction instruction, Instant now, String errorCode, int maxAttempts) {
        boolean exhausted = instruction.attemptCount() >= maxAttempts;
        long delaySeconds = Math.min(3600, 5L << Math.min(instruction.attemptCount() - 1, 9));
        jdbcTemplate.update("""
                UPDATE lifecycle_hold_instruction_outbox
                SET delivery_status = ?, next_attempt_at = ?, lease_expires_at = NULL,
                    last_error_code = ?
                WHERE outbox_id = ? AND delivery_status = 'PROCESSING'
                """, exhausted ? "RECONCILIATION_REQUIRED" : "PENDING",
                Timestamp.from(now.plusSeconds(delaySeconds)), boundedCode(errorCode), instruction.outboxId());
    }

    private static String boundedCode(String value) {
        if (value == null || value.isBlank()) {
            return "DELIVERY_FAILED";
        }
        return value.replaceAll("[^A-Za-z0-9_]", "_").substring(0,
                Math.min(64, value.replaceAll("[^A-Za-z0-9_]", "_").length()));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
