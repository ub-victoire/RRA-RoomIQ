package rw.rra.roomiq.lifecycle.legalhold;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.lifecycle.legalhold.api.AmendLegalHoldRequest;
import rw.rra.roomiq.lifecycle.legalhold.api.CreateLegalHoldRequest;
import rw.rra.roomiq.lifecycle.legalhold.api.LegalHoldEvidenceRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class LegalHoldService {
    private static final Pattern CATEGORY_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern RECORD_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern SOURCE_SERVICE = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern RECORD_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final LegalHoldRepository repository;
    private final LegalHoldEventWriter eventWriter;
    private final Clock clock;

    @Autowired
    public LegalHoldService(LegalHoldRepository repository, LegalHoldEventWriter eventWriter) {
        this(repository, eventWriter, Clock.systemUTC());
    }

    LegalHoldService(LegalHoldRepository repository, LegalHoldEventWriter eventWriter, Clock clock) {
        this.repository = repository;
        this.eventWriter = eventWriter;
        this.clock = clock;
    }

    @Transactional
    public LegalHoldRecord create(CreateLegalHoldRequest request, UUID actor, String idempotencyKey,
                                  String correlationId) {
        requireActor(actor);
        validateIdempotencyKey(idempotencyKey);
        LegalHoldScope scope = validateScope(request.scope());
        String holdReference = requireText(request.holdReference(), 128, "holdReference");
        String reason = requireText(request.reason(), 1000, "reason");
        String evidenceReference = requireText(request.supportingEvidenceReference(), 256,
                "supportingEvidenceReference");
        CreateLegalHoldRequest normalized = new CreateLegalHoldRequest(request.holdId(), holdReference, reason,
                scope, evidenceReference);
        String requestHash = eventWriter.digest(normalized);

        eventWriter.lockAggregate(request.holdId());
        UUID eventId = eventWriter.eventId(actor, "HOLD_CREATED", idempotencyKey, request.holdId());
        if (eventWriter.alreadyApplied(eventId, request.holdId(), requestHash)) {
            return requireHold(request.holdId());
        }
        if (repository.findById(request.holdId()).isPresent()) {
            throw conflict("LEGAL_HOLD_EXISTS", "Legal hold identifier already exists");
        }

        Instant now = clock.instant();
        LegalHoldRecord created = new LegalHoldRecord(request.holdId(), 1, holdReference, reason, scope,
                LegalHoldStatus.DRAFT, actor, now, actor, now, null, null, null,
                null, null, null, null, null, null, evidenceReference, evidenceReference,
                correlationId, eventId);
        eventWriter.append(actor, created.holdId(), eventId, "HOLD_CREATED", requestHash,
                idempotencyKey, correlationId, null, created);
        repository.insert(created);
        return created;
    }

    @Transactional(readOnly = true)
    public LegalHoldRecord get(UUID holdId) {
        return requireHold(holdId);
    }

    @Transactional
    public LegalHoldRecord submit(UUID holdId, long expectedVersion, UUID actor, String idempotencyKey,
                                  String correlationId) {
        return transition(holdId, expectedVersion, actor, idempotencyKey, correlationId,
                "HOLD_SUBMITTED", Map.of("expectedVersion", expectedVersion), current -> {
                    requireStatus(current, LegalHoldStatus.DRAFT);
                    requireActorIsInitiator(current, actor);
                    return updated(current, actor, LegalHoldStatus.PENDING_APPROVAL, clock.instant(),
                            current.reason(), current.scope(), current.effectiveFrom(), current.approvedBy(),
                            current.approvedAt(), current.releaseRequestedBy(), current.releaseRequestedAt(),
                            current.releaseRequestEvidenceReference(), current.releasedBy(), current.releasedAt(),
                            current.releaseVerificationReference(), current.supportingEvidenceReference(),
                            current.lastEvidenceReference(), correlationId);
                });
    }

    @Transactional
    public LegalHoldRecord approve(UUID holdId, long expectedVersion, UUID actor,
                                   LegalHoldEvidenceRequest request, String idempotencyKey,
                                   String correlationId) {
        String evidenceReference = requireText(request.evidenceReference(), 256, "evidenceReference");
        return transition(holdId, expectedVersion, actor, idempotencyKey, correlationId,
                "HOLD_APPROVED", Map.of("expectedVersion", expectedVersion,
                        "evidenceReference", evidenceReference), current -> {
                    requireStatus(current, LegalHoldStatus.PENDING_APPROVAL);
                    requireDifferentActor(current.initiatingActor(), actor, "A hold initiator cannot approve their hold");
                    Instant now = clock.instant();
                    return updated(current, actor, LegalHoldStatus.ACTIVE, now,
                            current.reason(), current.scope(), now, actor, now, null, null, null,
                            null, null, null, current.supportingEvidenceReference(), evidenceReference,
                            correlationId);
                });
    }

    @Transactional
    public LegalHoldRecord reject(UUID holdId, long expectedVersion, UUID actor,
                                  LegalHoldEvidenceRequest request, String idempotencyKey,
                                  String correlationId) {
        String evidenceReference = requireText(request.evidenceReference(), 256, "evidenceReference");
        return transition(holdId, expectedVersion, actor, idempotencyKey, correlationId,
                "HOLD_REJECTED", Map.of("expectedVersion", expectedVersion,
                        "evidenceReference", evidenceReference), current -> {
                    requireStatus(current, LegalHoldStatus.PENDING_APPROVAL);
                    requireDifferentActor(current.initiatingActor(), actor, "A hold initiator cannot reject their hold");
                    return updated(current, actor, LegalHoldStatus.REJECTED, clock.instant(),
                            current.reason(), current.scope(), null, null, null, null, null, null,
                            null, null, null, current.supportingEvidenceReference(), evidenceReference,
                            correlationId);
                });
    }

    @Transactional
    public LegalHoldRecord amend(UUID holdId, long expectedVersion, UUID actor, AmendLegalHoldRequest request,
                                 String idempotencyKey, String correlationId) {
        LegalHoldScope scope = validateScope(request.scope());
        String reason = requireText(request.reason(), 1000, "reason");
        String evidenceReference = requireText(request.evidenceReference(), 256, "evidenceReference");
        AmendLegalHoldRequest normalized = new AmendLegalHoldRequest(reason, scope, evidenceReference);
        return transition(holdId, expectedVersion, actor, idempotencyKey, correlationId,
                "HOLD_AMENDED", Map.of("expectedVersion", expectedVersion, "request", normalized), scope,
                current -> {
                    requireStatus(current, LegalHoldStatus.ACTIVE);
                    return updated(current, actor, LegalHoldStatus.ACTIVE, clock.instant(),
                            reason, scope, current.effectiveFrom(), current.approvedBy(), current.approvedAt(),
                            null, null, null, null, null, null, evidenceReference, evidenceReference,
                            correlationId);
                });
    }

    @Transactional
    public LegalHoldRecord requestRelease(UUID holdId, long expectedVersion, UUID actor,
                                          LegalHoldEvidenceRequest request, String idempotencyKey,
                                          String correlationId) {
        String evidenceReference = requireText(request.evidenceReference(), 256, "evidenceReference");
        return transition(holdId, expectedVersion, actor, idempotencyKey, correlationId,
                "HOLD_RELEASE_REQUESTED", Map.of("expectedVersion", expectedVersion,
                        "evidenceReference", evidenceReference), current -> {
                    requireStatus(current, LegalHoldStatus.ACTIVE);
                    requireDifferentActor(current.initiatingActor(), actor,
                            "A hold initiator cannot request release of their hold");
                    Instant now = clock.instant();
                    return updated(current, actor, LegalHoldStatus.RELEASE_PENDING, now,
                            current.reason(), current.scope(), current.effectiveFrom(), current.approvedBy(),
                            current.approvedAt(), actor, now, evidenceReference, null, null, null,
                            current.supportingEvidenceReference(), evidenceReference, correlationId);
                });
    }

    @Transactional
    public LegalHoldRecord approveRelease(UUID holdId, long expectedVersion, UUID actor,
                                         LegalHoldEvidenceRequest request, String idempotencyKey,
                                         String correlationId) {
        String verificationReference = requireText(request.evidenceReference(), 256, "evidenceReference");
        return transition(holdId, expectedVersion, actor, idempotencyKey, correlationId,
                "HOLD_RELEASED", Map.of("expectedVersion", expectedVersion,
                        "verificationReference", verificationReference), current -> {
                    requireStatus(current, LegalHoldStatus.RELEASE_PENDING);
                    requireDifferentActor(current.initiatingActor(), actor,
                            "A hold initiator cannot release their hold");
                    requireDifferentActor(current.releaseRequestedBy(), actor,
                            "A release requester cannot approve the release");
                    Instant now = clock.instant();
                    return updated(current, actor, LegalHoldStatus.RELEASED, now,
                            current.reason(), current.scope(), current.effectiveFrom(), current.approvedBy(),
                            current.approvedAt(), current.releaseRequestedBy(), current.releaseRequestedAt(),
                            current.releaseRequestEvidenceReference(), actor, now, verificationReference,
                            current.supportingEvidenceReference(), verificationReference, correlationId);
                });
    }

    private LegalHoldRecord transition(UUID holdId, long expectedVersion, UUID actor, String idempotencyKey,
                                      String correlationId, String operation, Object request,
                                      java.util.function.Function<LegalHoldRecord, LegalHoldRecord> change) {
        return transition(holdId, expectedVersion, actor, idempotencyKey, correlationId, operation, request,
                null, change);
    }

    private LegalHoldRecord transition(UUID holdId, long expectedVersion, UUID actor, String idempotencyKey,
                                      String correlationId, String operation, Object request,
                                      LegalHoldScope additionalScope,
                                      java.util.function.Function<LegalHoldRecord, LegalHoldRecord> change) {
        requireActor(actor);
        validateIdempotencyKey(idempotencyKey);
        eventWriter.lockAggregate(holdId);
        LegalHoldRecord current = repository.findForUpdate(holdId)
                .orElseThrow(() -> notFound("LEGAL_HOLD_NOT_FOUND", "Legal hold was not found"));
        UUID eventId = eventWriter.eventId(actor, operation, idempotencyKey, holdId);
        String requestHash = eventWriter.digest(request);
        if (eventWriter.alreadyApplied(eventId, holdId, requestHash)) {
            return current;
        }
        if (current.version() != expectedVersion) {
            throw conflict("STALE_LEGAL_HOLD_VERSION", "Legal hold version does not match If-Match");
        }
        eventWriter.lockScopes(current.scope(), additionalScope);
        LegalHoldRecord changed = change.apply(current);
        changed = withEventId(changed, eventId);
        eventWriter.append(actor, holdId, eventId, operation, requestHash,
                idempotencyKey, correlationId, current, changed);
        repository.update(changed);
        return changed;
    }

    private LegalHoldRecord requireHold(UUID holdId) {
        return repository.findById(holdId)
                .orElseThrow(() -> notFound("LEGAL_HOLD_NOT_FOUND", "Legal hold was not found"));
    }

    private static LegalHoldScope validateScope(LegalHoldScope scope) {
        if (scope == null || scope.type() == null) {
            throw badRequest("LEGAL_HOLD_SCOPE_INVALID", "A supported legal hold scope is required");
        }
        if (scope.type() == LegalHoldScopeType.RECORD) {
            if (!matches(SOURCE_SERVICE, scope.sourceService())
                    || !matches(RECORD_TYPE, scope.recordType())
                    || !matches(RECORD_ID, scope.recordId())
                    || scope.categoryCode() != null) {
                throw badRequest("LEGAL_HOLD_SCOPE_INVALID",
                        "Record scope requires sourceService, recordType, and recordId only");
            }
        } else if (scope.type() == LegalHoldScopeType.CATEGORY) {
            if (!matches(CATEGORY_CODE, scope.categoryCode())
                    || scope.sourceService() != null || scope.recordType() != null || scope.recordId() != null) {
                throw badRequest("LEGAL_HOLD_SCOPE_INVALID", "Category scope requires categoryCode only");
            }
        } else {
            throw badRequest("LEGAL_HOLD_SCOPE_UNSUPPORTED", "Only RECORD and CATEGORY scopes are supported");
        }
        return scope;
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static String requireText(String value, int maxLength, String field) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty() || result.length() > maxLength) {
            throw badRequest("LEGAL_HOLD_FIELD_INVALID", field + " is required and must be at most "
                    + maxLength + " characters");
        }
        return result;
    }

    private static void validateIdempotencyKey(String value) {
        requireText(value, 128, "Idempotency-Key");
    }

    private static void requireActor(UUID actor) {
        if (actor == null) {
            throw new IllegalStateException("Trusted Identity authorization actor is missing");
        }
    }

    private static void requireStatus(LegalHoldRecord hold, LegalHoldStatus expected) {
        if (hold.status() != expected) {
            throw conflict("LEGAL_HOLD_TRANSITION_INVALID",
                    "Legal hold must be in " + expected + " state for this action");
        }
    }

    private static void requireActorIsInitiator(LegalHoldRecord hold, UUID actor) {
        if (!hold.initiatingActor().equals(actor)) {
            throw new DomainException(HttpStatus.FORBIDDEN, "LEGAL_HOLD_INITIATOR_REQUIRED",
                    "Only the initiating actor may submit this draft");
        }
    }

    private static void requireDifferentActor(UUID otherActor, UUID actor, String message) {
        if (otherActor != null && otherActor.equals(actor)) {
            throw new DomainException(HttpStatus.FORBIDDEN, "LEGAL_HOLD_SEPARATION_OF_DUTIES", message);
        }
    }

    private static LegalHoldRecord updated(LegalHoldRecord old, UUID actor, LegalHoldStatus status, Instant updatedAt,
                                           String reason, LegalHoldScope scope, Instant effectiveFrom,
                                           UUID approvedBy, Instant approvedAt, UUID releaseRequestedBy,
                                           Instant releaseRequestedAt, String releaseRequestEvidenceReference,
                                           UUID releasedBy, Instant releasedAt, String releaseVerificationReference,
                                           String supportingEvidenceReference, String lastEvidenceReference,
                                           String correlationId) {
        return new LegalHoldRecord(old.holdId(), old.version() + 1, old.holdReference(), reason, scope, status,
                old.initiatingActor(), old.createdAt(), actor, updatedAt, effectiveFrom, approvedBy, approvedAt,
                releaseRequestedBy, releaseRequestedAt, releaseRequestEvidenceReference, releasedBy, releasedAt,
                releaseVerificationReference, supportingEvidenceReference, lastEvidenceReference, correlationId,
                old.lastEventId());
    }

    private static LegalHoldRecord withEventId(LegalHoldRecord record, UUID eventId) {
        return new LegalHoldRecord(record.holdId(), record.version(), record.holdReference(), record.reason(),
                record.scope(), record.status(), record.initiatingActor(), record.createdAt(), record.updatedBy(),
                record.updatedAt(), record.effectiveFrom(), record.approvedBy(), record.approvedAt(),
                record.releaseRequestedBy(), record.releaseRequestedAt(),
                record.releaseRequestEvidenceReference(), record.releasedBy(), record.releasedAt(),
                record.releaseVerificationReference(), record.supportingEvidenceReference(),
                record.lastEvidenceReference(), record.correlationId(), eventId);
    }

    private static DomainException badRequest(String code, String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static DomainException conflict(String code, String message) {
        return new DomainException(HttpStatus.CONFLICT, code, message);
    }

    private static DomainException notFound(String code, String message) {
        return new DomainException(HttpStatus.NOT_FOUND, code, message);
    }
}
