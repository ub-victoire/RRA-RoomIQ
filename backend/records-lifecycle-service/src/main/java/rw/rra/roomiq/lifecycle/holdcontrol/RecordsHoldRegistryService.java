package rw.rra.roomiq.lifecycle.holdcontrol;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.lifecycle.sourceevidence.SourceOwnerEvidenceRepository;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class RecordsHoldRegistryService {
    private static final String BOOKING_SERVICE = "booking-service";
    private static final String BOOKING_REQUEST = "BOOKING_REQUEST";
    private final RecordsHoldRepository repository;
    private final RecordsHoldEventWriter eventWriter;
    private final RecordsHoldInstructionOutboxRepository instructionOutbox;
    private final SourceOwnerEvidenceRepository sourceEvidenceRepository;
    private final Clock clock;

    @Autowired
    public RecordsHoldRegistryService(RecordsHoldRepository repository,
                                      RecordsHoldEventWriter eventWriter,
                                      SourceOwnerEvidenceRepository sourceEvidenceRepository,
                                      RecordsHoldInstructionOutboxRepository instructionOutbox) {
        this(repository, eventWriter, sourceEvidenceRepository, instructionOutbox, Clock.systemUTC());
    }

    RecordsHoldRegistryService(RecordsHoldRepository repository,
                               RecordsHoldEventWriter eventWriter,
                               SourceOwnerEvidenceRepository sourceEvidenceRepository,
                               RecordsHoldInstructionOutboxRepository instructionOutbox,
                               Clock clock) {
        this.repository = repository;
        this.eventWriter = eventWriter;
        this.sourceEvidenceRepository = sourceEvidenceRepository;
        this.instructionOutbox = instructionOutbox;
        this.clock = clock;
    }

    @Transactional
    public RecordsLegalHold create(CreateRecordsHoldRequest request, UUID actorId, String idempotencyKey,
                                   String correlationId) {
        validateRequest(request, actorId, idempotencyKey);
        String requestHash = eventWriter.digest(request);
        if (eventWriter.exists(actorId, "HOLD_CREATED", idempotencyKey, requestHash, request.holdId())) {
            return requireHold(request.holdId());
        }

        sourceEvidenceRepository.lockRecord(request.sourceService(), request.recordType(), request.recordId());
        if (eventWriter.exists(actorId, "HOLD_CREATED", idempotencyKey, requestHash, request.holdId())) {
            return requireHold(request.holdId());
        }
        if (repository.findById(request.holdId()).isPresent()) {
            throw conflict("LEGAL_HOLD_ALREADY_EXISTS", "Legal-hold identifier already exists");
        }

        RecordsHoldStatus status = repository.hasBlockingHold(
                request.sourceService(), request.recordType(), request.recordId())
                ? RecordsHoldStatus.CONFLICTED : RecordsHoldStatus.PENDING_BLOCK;
        RecordsLegalHold hold = new RecordsLegalHold(
                request.holdId(), request.sourceService(), request.recordType(), request.recordId(),
                request.instructionReference(), status, actorId, clock.instant(),
                null, null, null, null, null, null, 0);
        boolean eventCreated = eventWriter.append(actorId, "HOLD_CREATED", idempotencyKey, requestHash,
                hold.holdId(), null, status.name(), request.instructionReference(), correlationId, clock.instant());
        if (!eventCreated) {
            return requireHold(hold.holdId());
        }
        repository.insert(hold);
        instructionOutbox.enqueue(hold, 1, "APPLY", correlationId, hold.createdAt());
        return hold;
    }

    @Transactional(readOnly = true)
    public RecordsLegalHold get(UUID holdId) {
        return requireHold(holdId);
    }

    @Transactional
    public RecordsLegalHold requestRelease(UUID holdId, ReleaseRecordsHoldRequest request, UUID actorId,
                                           String idempotencyKey, String correlationId) {
        validateTransition(actorId, idempotencyKey);
        if (request == null || !validReference(request.releaseReference())) {
            throw invalidRequest("A bounded release decision reference is required");
        }
        RecordsLegalHold initial = requireHold(holdId);
        String requestHash = eventWriter.digest(request);
        if (eventWriter.exists(actorId, "HOLD_RELEASE_REQUESTED", idempotencyKey, requestHash, holdId)) {
            return initial;
        }

        sourceEvidenceRepository.lockRecord(initial.sourceService(), initial.recordType(), initial.recordId());
        RecordsLegalHold hold = repository.findByIdForUpdate(holdId).orElseThrow(
                () -> notFound("LEGAL_HOLD_NOT_FOUND", "Legal hold was not found"));
        if (eventWriter.exists(actorId, "HOLD_RELEASE_REQUESTED", idempotencyKey, requestHash, holdId)) {
            return hold;
        }
        if (hold.status() != RecordsHoldStatus.PENDING_BLOCK
                && hold.status() != RecordsHoldStatus.CONFLICTED) {
            throw conflict("LEGAL_HOLD_TRANSITION_INVALID", "Legal hold is not awaiting release");
        }

        var now = clock.instant();
        boolean eventCreated = eventWriter.append(actorId, "HOLD_RELEASE_REQUESTED", idempotencyKey,
                requestHash, holdId, hold.status().name(), RecordsHoldStatus.RELEASE_PENDING.name(),
                request.releaseReference(), correlationId, now);
        if (!eventCreated) {
            return requireHold(holdId);
        }
        repository.requestRelease(hold, actorId, now, request.releaseReference());
        return requireHold(holdId);
    }

    @Transactional
    public RecordsLegalHold verifyRelease(UUID holdId, VerifyRecordsHoldReleaseRequest request, UUID actorId,
                                          String idempotencyKey, String correlationId) {
        validateTransition(actorId, idempotencyKey);
        if (request == null || !validReference(request.verificationReference())) {
            throw invalidRequest("A bounded release verification reference is required");
        }
        RecordsLegalHold initial = requireHold(holdId);
        String requestHash = eventWriter.digest(request);
        if (eventWriter.exists(actorId, "HOLD_RELEASE_VERIFIED", idempotencyKey, requestHash, holdId)) {
            return initial;
        }

        sourceEvidenceRepository.lockRecord(initial.sourceService(), initial.recordType(), initial.recordId());
        RecordsLegalHold hold = repository.findByIdForUpdate(holdId).orElseThrow(
                () -> notFound("LEGAL_HOLD_NOT_FOUND", "Legal hold was not found"));
        if (eventWriter.exists(actorId, "HOLD_RELEASE_VERIFIED", idempotencyKey, requestHash, holdId)) {
            return hold;
        }
        if (hold.status() != RecordsHoldStatus.RELEASE_PENDING || hold.verifiedAt() != null) {
            throw conflict("LEGAL_HOLD_TRANSITION_INVALID", "Legal hold has no pending release to verify");
        }
        if (actorId.equals(hold.releaseRequestedByUserId())) {
            throw new DomainException(HttpStatus.FORBIDDEN, "INDEPENDENT_VERIFICATION_REQUIRED",
                    "Release verification must be performed by a different authorized user");
        }

        var now = clock.instant();
        boolean eventCreated = eventWriter.append(actorId, "HOLD_RELEASE_VERIFIED", idempotencyKey,
                requestHash, holdId, hold.status().name(), RecordsHoldStatus.RELEASE_ENFORCEMENT_PENDING.name(),
                request.verificationReference(), correlationId, now);
        if (!eventCreated) {
            return requireHold(holdId);
        }
        repository.verifyRelease(hold, actorId, now, request.verificationReference());
        instructionOutbox.enqueue(hold, hold.version() + 1, "RELEASE", correlationId, now);
        return requireHold(holdId);
    }

    @Transactional(readOnly = true)
    public List<rw.rra.roomiq.lifecycle.retention.HoldStatus> statusesForRecord(
            String sourceService, String recordType, String recordId) {
        return repository.findStatuses(sourceService, recordType, recordId);
    }

    private RecordsLegalHold requireHold(UUID holdId) {
        return repository.findById(holdId).orElseThrow(
                () -> notFound("LEGAL_HOLD_NOT_FOUND", "Legal hold was not found"));
    }

    private static void validateRequest(CreateRecordsHoldRequest request, UUID actorId, String idempotencyKey) {
        validateTransition(actorId, idempotencyKey);
        if (request == null || request.holdId() == null
                || !BOOKING_SERVICE.equals(request.sourceService())
                || !BOOKING_REQUEST.equals(request.recordType())
                || request.recordId() == null
                || !request.recordId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !validReference(request.instructionReference())) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_LEGAL_HOLD",
                    "Legal hold must identify a supported Booking request and a bounded instruction reference");
        }
    }

    private static void validateTransition(UUID actorId, String idempotencyKey) {
        if (actorId == null) {
            throw new IllegalStateException("Trusted Identity authorization actor is missing");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "A nonblank Idempotency-Key of at most 128 characters is required");
        }
    }

    private static boolean validReference(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    }

    private static DomainException notFound(String code, String message) {
        return new DomainException(HttpStatus.NOT_FOUND, code, message);
    }

    private static DomainException conflict(String code, String message) {
        return new DomainException(HttpStatus.CONFLICT, code, message);
    }

    private static DomainException invalidRequest(String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, "INVALID_LEGAL_HOLD", message);
    }
}
