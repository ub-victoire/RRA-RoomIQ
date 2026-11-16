package rw.rra.roomiq.booking.domain.service;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.booking.domain.entity.BookingRecordLegalHold;
import rw.rra.roomiq.booking.domain.repository.BookingRecordLegalHoldRepository;
import rw.rra.roomiq.booking.domain.repository.BookingRequestRepository;
import rw.rra.roomiq.booking.integration.BookingHoldAcknowledgement;
import rw.rra.roomiq.booking.integration.BookingHoldInstruction;
import rw.rra.roomiq.common.web.DomainException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class BookingSourceHoldService {
    private static final String TRUSTED_SOURCE = "records-lifecycle-service";
    private static final String RECORD_OWNER = "booking-service";
    private static final String RECORD_TYPE = "BOOKING_REQUEST";
    private final BookingRequestRepository bookingRequests;
    private final BookingRecordLegalHoldRepository holds;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public BookingSourceHoldService(BookingRequestRepository bookingRequests,
                                    BookingRecordLegalHoldRepository holds,
                                    JdbcTemplate jdbcTemplate) {
        this(bookingRequests, holds, jdbcTemplate, Clock.systemUTC());
    }

    BookingSourceHoldService(BookingRequestRepository bookingRequests,
                             BookingRecordLegalHoldRepository holds,
                             JdbcTemplate jdbcTemplate, Clock clock) {
        this.bookingRequests = bookingRequests;
        this.holds = holds;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional
    public BookingHoldAcknowledgement receive(BookingHoldInstruction instruction, String authenticatedSource) {
        validate(instruction, authenticatedSource, clock.instant());
        String requestDigest = digest(instruction);
        var record = bookingRequests.findByIdForUpdate(UUID.fromString(instruction.recordId()));
        if (record.isEmpty()) {
            return persist(instruction, requestDigest, BookingHoldAcknowledgement.Outcome.REJECTED,
                    null, null);
        }

        BookingRecordLegalHold current = holds.findByHoldIdForUpdate(instruction.holdId()).orElse(null);
        BookingHoldAcknowledgement.Outcome outcome = determineOutcome(instruction, requestDigest, current);
        String state = outcome == BookingHoldAcknowledgement.Outcome.ACCEPTED
                ? "APPLY".equals(instruction.operation()) ? "ACTIVE" : "RELEASED"
                : current == null ? null : current.getStatus().name();
        BookingHoldAcknowledgement acknowledgement = persist(
                instruction, requestDigest, outcome, state, (long) record.get().getVersion());
        if (outcome == BookingHoldAcknowledgement.Outcome.ACCEPTED) {
            Instant now = clock.instant();
            if ("APPLY".equals(instruction.operation())) {
                if (current == null) {
                    current = new BookingRecordLegalHold(instruction.holdId(), record.get().getId(),
                            BookingRecordLegalHold.Status.ACTIVE, instruction.instructionRevision(),
                            instruction.instructionId(), instruction.instructionReferenceSha256(),
                            requestDigest, instruction.correlationId(), now, null);
                    holds.saveAndFlush(current);
                } else {
                    current.applyRevision(instruction.instructionRevision(), instruction.instructionId(),
                            requestDigest,
                            instruction.instructionReferenceSha256(), instruction.correlationId(), now);
                    holds.saveAndFlush(current);
                }
            } else {
                current.releaseRevision(instruction.instructionRevision(), instruction.instructionId(),
                        requestDigest,
                        instruction.correlationId(), now);
                holds.saveAndFlush(current);
            }
        }
        return acknowledgement;
    }

    @Transactional(readOnly = true)
    public void requireNoActiveHold(UUID bookingRequestId) {
        if (holds.existsByBookingRequestIdAndStatus(bookingRequestId, BookingRecordLegalHold.Status.ACTIVE)) {
            throw new DomainException(HttpStatus.CONFLICT, "BOOKING_REQUEST_ON_LEGAL_HOLD",
                    "The booking record is protected by an active records hold");
        }
    }

    private BookingHoldAcknowledgement.Outcome determineOutcome(
            BookingHoldInstruction instruction, String digest, BookingRecordLegalHold current) {
        if (current == null) {
            if ("RELEASE".equals(instruction.operation())) {
                return BookingHoldAcknowledgement.Outcome.REJECTED;
            }
            return instruction.instructionRevision() == 1
                    ? BookingHoldAcknowledgement.Outcome.ACCEPTED
                    : BookingHoldAcknowledgement.Outcome.RECONCILIATION_REQUIRED;
        }
        if (!current.getBookingRequestId().toString().equals(instruction.recordId())) {
            return BookingHoldAcknowledgement.Outcome.CONFLICTED;
        }
        if (instruction.instructionRevision() == current.getInstructionRevision()) {
            return current.getLastInstructionId().equals(instruction.instructionId())
                    && current.getLastInstructionDigest().equals(digest)
                    && current.getInstructionReferenceSha256().equals(instruction.instructionReferenceSha256())
                    && (("APPLY".equals(instruction.operation())
                    && current.getStatus() == BookingRecordLegalHold.Status.ACTIVE)
                    || ("RELEASE".equals(instruction.operation())
                    && current.getStatus() == BookingRecordLegalHold.Status.RELEASED))
                    ? BookingHoldAcknowledgement.Outcome.DUPLICATE
                    : BookingHoldAcknowledgement.Outcome.CONFLICTED;
        }
        if (instruction.instructionRevision() < current.getInstructionRevision()) {
            return BookingHoldAcknowledgement.Outcome.STALE;
        }
        if (instruction.instructionRevision() != current.getInstructionRevision() + 1) {
            return BookingHoldAcknowledgement.Outcome.RECONCILIATION_REQUIRED;
        }
        if ("APPLY".equals(instruction.operation())
                || current.getStatus() != BookingRecordLegalHold.Status.ACTIVE) {
            return BookingHoldAcknowledgement.Outcome.CONFLICTED;
        }
        return BookingHoldAcknowledgement.Outcome.ACCEPTED;
    }

    private BookingHoldAcknowledgement persist(BookingHoldInstruction instruction, String digest,
                                                BookingHoldAcknowledgement.Outcome outcome,
                                                String state, Long bookingRequestVersion) {
        jdbcTemplate.update("""
                INSERT INTO booking_legal_hold_instruction_receipt (
                    receipt_id, instruction_id, hold_id, instruction_revision, operation,
                    booking_request_id, source_service, instruction_digest, outcome,
                    enforcement_status, booking_request_version, correlation_id, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), instruction.instructionId(), instruction.holdId(),
                instruction.instructionRevision(), instruction.operation(), UUID.fromString(instruction.recordId()),
                TRUSTED_SOURCE, digest, outcome.name(), state, bookingRequestVersion,
                instruction.correlationId(), Timestamp.from(clock.instant()));
        return new BookingHoldAcknowledgement(instruction.instructionId(), instruction.holdId(),
                instruction.instructionRevision(), outcome, state, bookingRequestVersion);
    }

    private static void validate(BookingHoldInstruction instruction, String authenticatedSource, Instant now) {
        if (instruction == null || instruction.contractVersion() != 1
                || instruction.instructionId() == null || instruction.holdId() == null
                || instruction.instructionRevision() < 1
                || !TRUSTED_SOURCE.equals(authenticatedSource)
                || !RECORD_OWNER.equals(instruction.sourceService())
                || !RECORD_TYPE.equals(instruction.recordType())
                || instruction.recordId() == null
                || !instruction.recordId().matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !("APPLY".equals(instruction.operation()) || "RELEASE".equals(instruction.operation()))
                || instruction.instructionReferenceSha256() == null
                || !instruction.instructionReferenceSha256().matches("[0-9a-f]{64}")
                || instruction.issuedAt() == null
                || instruction.issuedAt().isAfter(now.plusSeconds(30))
                || instruction.correlationId() != null && instruction.correlationId().length() > 128) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "BOOKING_HOLD_INSTRUCTION_INVALID",
                    "The records-hold instruction is invalid or unsupported");
        }
    }

    private static String digest(BookingHoldInstruction instruction) {
        String value = instruction.contractVersion() + "\n" + instruction.instructionId() + "\n"
                + instruction.holdId() + "\n" + instruction.instructionRevision() + "\n"
                + instruction.operation() + "\n" + instruction.sourceService() + "\n"
                + instruction.recordType() + "\n" + instruction.recordId() + "\n"
                + instruction.instructionReferenceSha256() + "\n" + instruction.issuedAt() + "\n"
                + instruction.correlationId();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
