package rw.rra.roomiq.booking.integration;

import java.time.Instant;
import java.util.UUID;

public record BookingHoldInstruction(
        int contractVersion,
        UUID instructionId,
        UUID holdId,
        long instructionRevision,
        String operation,
        String sourceService,
        String recordType,
        String recordId,
        String instructionReferenceSha256,
        Instant issuedAt,
        String correlationId) {
}
