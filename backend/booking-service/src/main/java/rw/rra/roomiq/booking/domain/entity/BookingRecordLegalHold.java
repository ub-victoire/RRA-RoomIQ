package rw.rra.roomiq.booking.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_record_legal_hold")
public class BookingRecordLegalHold extends BookingEntity {
    @Column(name = "hold_id", nullable = false, unique = true)
    private UUID holdId;

    @Column(name = "booking_request_id", nullable = false, updatable = false)
    private UUID bookingRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "instruction_revision", nullable = false)
    private long instructionRevision;

    @Column(name = "last_instruction_id", nullable = false)
    private UUID lastInstructionId;

    @Column(name = "last_instruction_digest", nullable = false, length = 64)
    private String lastInstructionDigest;

    @Column(name = "instruction_reference_sha256", nullable = false, length = 64)
    private String instructionReferenceSha256;

    @Column(name = "last_correlation_id", length = 128)
    private String lastCorrelationId;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected BookingRecordLegalHold() {
    }

    public BookingRecordLegalHold(UUID holdId, UUID bookingRequestId, Status status, long instructionRevision,
                                  UUID lastInstructionId, String instructionReferenceSha256,
                                  String lastInstructionDigest, String lastCorrelationId,
                                  Instant appliedAt, Instant releasedAt) {
        this.holdId = holdId;
        this.bookingRequestId = bookingRequestId;
        this.status = status;
        this.instructionRevision = instructionRevision;
        this.lastInstructionId = lastInstructionId;
        this.lastInstructionDigest = lastInstructionDigest;
        this.instructionReferenceSha256 = instructionReferenceSha256;
        this.lastCorrelationId = lastCorrelationId;
        this.appliedAt = appliedAt;
        this.releasedAt = releasedAt;
    }

    public UUID getHoldId() { return holdId; }
    public UUID getBookingRequestId() { return bookingRequestId; }
    public Status getStatus() { return status; }
    public long getInstructionRevision() { return instructionRevision; }
    public UUID getLastInstructionId() { return lastInstructionId; }
    public String getLastInstructionDigest() { return lastInstructionDigest; }
    public String getInstructionReferenceSha256() { return instructionReferenceSha256; }
    public String getLastCorrelationId() { return lastCorrelationId; }
    public Instant getAppliedAt() { return appliedAt; }
    public Instant getReleasedAt() { return releasedAt; }
    public long getVersion() { return version; }

    public void applyRevision(long revision, UUID instructionId, String instructionDigest, String referenceDigest,
                              String correlationId, Instant appliedAt) {
        this.instructionRevision = revision;
        this.lastInstructionId = instructionId;
        this.lastInstructionDigest = instructionDigest;
        this.instructionReferenceSha256 = referenceDigest;
        this.lastCorrelationId = correlationId;
        this.status = Status.ACTIVE;
        this.appliedAt = appliedAt;
        this.releasedAt = null;
    }

    public void releaseRevision(long revision, UUID instructionId, String instructionDigest,
                                String correlationId, Instant releasedAt) {
        this.instructionRevision = revision;
        this.lastInstructionId = instructionId;
        this.lastInstructionDigest = instructionDigest;
        this.lastCorrelationId = correlationId;
        this.status = Status.RELEASED;
        this.releasedAt = releasedAt;
    }

    public enum Status {
        ACTIVE,
        RELEASED
    }
}
