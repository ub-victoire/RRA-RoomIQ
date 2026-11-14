package rw.rra.roomiq.lifecycle.holdcontrol;

import java.time.Instant;
import java.util.UUID;

public record RecordsHoldInstruction(
        UUID outboxId,
        BookingHoldInstruction instruction,
        String deliveryStatus,
        int attemptCount,
        String lastAcknowledgementOutcome,
        String acknowledgedEnforcementStatus,
        Long acknowledgedBookingRequestVersion) {
    static RecordsHoldInstruction from(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        BookingHoldInstruction instruction = new BookingHoldInstruction(
                1,
                resultSet.getObject("instruction_id", UUID.class),
                resultSet.getObject("hold_id", UUID.class),
                resultSet.getLong("instruction_revision"),
                resultSet.getString("operation"),
                resultSet.getString("source_service"),
                resultSet.getString("record_type"),
                resultSet.getString("record_id"),
                resultSet.getString("instruction_reference_sha256"),
                resultSet.getTimestamp("issued_at").toInstant(),
                resultSet.getString("correlation_id"));
        return new RecordsHoldInstruction(resultSet.getObject("outbox_id", UUID.class), instruction,
                resultSet.getString("delivery_status"), resultSet.getInt("attempt_count"),
                resultSet.getString("last_acknowledgement_outcome"),
                resultSet.getString("acknowledged_enforcement_status"),
                resultSet.getObject("acknowledged_booking_request_version", Long.class));
    }
}
