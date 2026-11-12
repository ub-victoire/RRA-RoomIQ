package rw.rra.roomiq.booking.domain.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BookingSourceEvidenceOutboxRepository {
    private final JdbcTemplate jdbcTemplate;

    public BookingSourceEvidenceOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UUID> latestEventId(UUID bookingRequestId) {
        return jdbcTemplate.query("""
                SELECT event_id FROM booking_source_evidence_outbox
                WHERE booking_request_id = ?
                ORDER BY source_revision DESC
                LIMIT 1
                """, resultSet -> resultSet.next()
                        ? Optional.of(resultSet.getObject(1, UUID.class)) : Optional.empty(), bookingRequestId);
    }

    public Optional<BookingSourceEvidenceOutboxEntry> findByRevision(UUID bookingRequestId, long revision) {
        return jdbcTemplate.query("""
                SELECT * FROM booking_source_evidence_outbox
                WHERE booking_request_id = ? AND source_revision = ?
                """, (resultSet, rowNumber) -> map(resultSet), bookingRequestId, revision)
                .stream().findFirst();
    }

    public void insert(BookingRequest request, long revision, UUID eventId, UUID supersedesEventId,
                       String categoryCode, String startBasisCode, Instant authoritativeStartAt,
                       Instant occurredAt, Instant observedAt, Instant closedAt, Instant decisionAt,
                       String correlationId, String deliveryStatus) {
        jdbcTemplate.update("""
                INSERT INTO booking_source_evidence_outbox (
                    id, booking_request_id, request_type, source_revision, event_id, supersedes_event_id,
                    category_code, start_basis_code, authoritative_start_at, current_lifecycle_status,
                    record_created_at, occurred_at, observed_at, closed_at, decision_at, correlation_id,
                    delivery_status, next_attempt_at, attempt_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, UUID.randomUUID(), request.getId(), request.getRequestType().name(), revision, eventId,
                supersedesEventId, categoryCode, startBasisCode, timestamp(authoritativeStartAt),
                request.getStatus().name(), timestamp(request.getCreatedAt()), timestamp(occurredAt),
                timestamp(observedAt), timestamp(closedAt), timestamp(decisionAt), correlationId,
                deliveryStatus, Timestamp.from(observedAt));
    }

    public List<BookingSourceEvidenceOutboxEntry> claimDue(Instant now, int limit) {
        return jdbcTemplate.query("""
                SELECT current.*
                FROM booking_source_evidence_outbox current
                WHERE current.delivery_status = 'PENDING'
                  AND current.next_attempt_at <= ?
                  AND NOT EXISTS (
                      SELECT 1 FROM booking_source_evidence_outbox earlier
                      WHERE earlier.booking_request_id = current.booking_request_id
                        AND earlier.source_revision < current.source_revision
                        AND earlier.delivery_status <> 'DELIVERED'
                  )
                ORDER BY current.next_attempt_at, current.created_at
                LIMIT ?
                FOR UPDATE OF current SKIP LOCKED
                """, (resultSet, rowNumber) -> map(resultSet), Timestamp.from(now), limit);
    }

    public List<BookingSourceEvidenceOutboxEntry> configurationBlocked(int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM booking_source_evidence_outbox
                WHERE delivery_status = 'CONFIGURATION_BLOCKED'
                ORDER BY created_at
                LIMIT ?
                """, (resultSet, rowNumber) -> map(resultSet), limit);
    }

    public void applyMapping(UUID rowId, String categoryCode, String startBasisCode,
                             Instant authoritativeStartAt) {
        jdbcTemplate.update("""
                UPDATE booking_source_evidence_outbox
                SET category_code = ?, start_basis_code = ?, authoritative_start_at = ?,
                    delivery_status = 'PENDING', next_attempt_at = CURRENT_TIMESTAMP,
                    last_error_code = NULL
                WHERE id = ? AND delivery_status = 'CONFIGURATION_BLOCKED'
                """, categoryCode, startBasisCode, timestamp(authoritativeStartAt), rowId);
    }

    public void markDelivered(UUID rowId, Instant deliveredAt) {
        jdbcTemplate.update("""
                UPDATE booking_source_evidence_outbox
                SET delivery_status = 'DELIVERED', delivered_at = ?, last_error_code = NULL
                WHERE id = ? AND delivery_status = 'PENDING'
                """, Timestamp.from(deliveredAt), rowId);
    }

    public void defer(UUID rowId, Instant retryAt, String errorCode, boolean reconciliationRequired) {
        jdbcTemplate.update("""
                UPDATE booking_source_evidence_outbox
                SET attempt_count = attempt_count + 1,
                    next_attempt_at = ?,
                    last_error_code = ?,
                    delivery_status = ?
                WHERE id = ? AND delivery_status = 'PENDING'
                """, Timestamp.from(retryAt), errorCode,
                reconciliationRequired ? "RECONCILIATION_REQUIRED" : "PENDING", rowId);
    }

    public void requireReconciliation(UUID rowId, String errorCode) {
        jdbcTemplate.update("""
                UPDATE booking_source_evidence_outbox
                SET delivery_status = 'RECONCILIATION_REQUIRED', last_error_code = ?
                WHERE id = ? AND delivery_status = 'PENDING'
                """, errorCode, rowId);
    }

    private static BookingSourceEvidenceOutboxEntry map(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new BookingSourceEvidenceOutboxEntry(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("booking_request_id", UUID.class),
                resultSet.getString("request_type"),
                resultSet.getLong("source_revision"),
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("supersedes_event_id", UUID.class),
                resultSet.getString("category_code"),
                resultSet.getString("start_basis_code"),
                instant(resultSet.getTimestamp("authoritative_start_at")),
                resultSet.getString("current_lifecycle_status"),
                instant(resultSet.getTimestamp("record_created_at")),
                instant(resultSet.getTimestamp("occurred_at")),
                instant(resultSet.getTimestamp("observed_at")),
                instant(resultSet.getTimestamp("closed_at")),
                instant(resultSet.getTimestamp("decision_at")),
                resultSet.getString("correlation_id"),
                resultSet.getInt("attempt_count"));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
