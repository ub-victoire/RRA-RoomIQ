package rw.rra.roomiq.booking.domain.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.booking.domain.dto.BookingExtensionDecisionRequest;
import rw.rra.roomiq.booking.domain.dto.BookingExtensionResponse;
import rw.rra.roomiq.booking.domain.dto.CreateBookingExtensionRequest;
import rw.rra.roomiq.booking.domain.entity.BookingExtension;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;
import rw.rra.roomiq.booking.domain.entity.Reservation;
import rw.rra.roomiq.booking.domain.entity.ReservationOccurrence;
import rw.rra.roomiq.booking.domain.enums.ApprovalDecisionType;
import rw.rra.roomiq.booking.domain.enums.BookingExtensionStatus;
import rw.rra.roomiq.booking.domain.repository.BookingExtensionRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationOccurrenceRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationRepository;
import rw.rra.roomiq.booking.integration.BookingAuthorizationClient;
import rw.rra.roomiq.booking.integration.BookingAuthorizationRequest;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient.BookingRequestFacts;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient.ValidatedBookingReferences;
import rw.rra.roomiq.common.web.DomainException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static rw.rra.roomiq.booking.domain.enums.BookingExtensionStatus.CANCELLED;
import static rw.rra.roomiq.booking.domain.enums.BookingExtensionStatus.PENDING;
import static rw.rra.roomiq.booking.domain.enums.ApprovalDecisionType.APPROVED;

@Service
public class BookingExtensionService {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final String EXTENSION_KEY_CONSTRAINT = "uq_booking_extension_idempotency_key";
    private static final String RESERVATION_OVERLAP_CONSTRAINT = "ex_reservation_room_occupied_period";
    private static final String OCCURRENCE_OVERLAP_CONSTRAINT =
            "ex_reservation_occurrence_room_occupied_period";

    private final ReservationRepository reservations;
    private final ReservationOccurrenceRepository occurrences;
    private final BookingExtensionRepository extensions;
    private final BookingAuthorizationClient authorizationClient;
    private final BookingOwnerServicesClient ownerServicesClient;
    private final PostgresIdempotencyLock idempotencyLock;
    private final BookingOccupancyOutboxService occupancyOutbox;
    private final BookingDomainEventOutboxService domainEvents;
    private final Clock clock;
    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    public BookingExtensionService(ReservationRepository reservations,
                                   ReservationOccurrenceRepository occurrences,
                                   BookingExtensionRepository extensions,
                                   BookingAuthorizationClient authorizationClient,
                                   BookingOwnerServicesClient ownerServicesClient,
                                   PostgresIdempotencyLock idempotencyLock,
                                   BookingOccupancyOutboxService occupancyOutbox,
                                   BookingDomainEventOutboxService domainEvents) {
        this(reservations, occurrences, extensions, authorizationClient, ownerServicesClient,
                idempotencyLock, occupancyOutbox, domainEvents, Clock.systemUTC());
    }

    BookingExtensionService(ReservationRepository reservations,
                            ReservationOccurrenceRepository occurrences,
                            BookingExtensionRepository extensions,
                            BookingAuthorizationClient authorizationClient,
                            BookingOwnerServicesClient ownerServicesClient,
                            PostgresIdempotencyLock idempotencyLock, BookingOccupancyOutboxService occupancyOutbox,
                            BookingDomainEventOutboxService domainEvents, Clock clock) {
        this.reservations = reservations;
        this.occurrences = occurrences;
        this.extensions = extensions;
        this.authorizationClient = authorizationClient;
        this.ownerServicesClient = ownerServicesClient;
        this.idempotencyLock = idempotencyLock;
        this.occupancyOutbox = occupancyOutbox;
        this.domainEvents = domainEvents;
        this.clock = clock;
    }

    @Transactional
    public BookingExtensionResponse request(UUID reservationId, CreateBookingExtensionRequest request,
                                            String idempotencyKey) {
        validateRequest(reservationId, request, idempotencyKey);
        Reservation reservation = lockReservation(reservationId);
        BookingRequest bookingRequest = reservation.getBookingRequest();
        UUID actor = authorizeRequester(reservation);

        if (reservation.getRecurrenceRuleId() != null || bookingRequest.getRecurrenceRuleId() != null) {
            throw recurringExtensionUnsupported();
        }
        Instant requestedEnd = normalized(request.requestedEndAt());
        if (!requestedEnd.isAfter(reservation.getEndAt())) {
            throw invalidExtension();
        }

        if (idempotencyKey != null) {
            idempotencyLock.acquire("booking-extension:create", idempotencyKey);
            var existing = extensions.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                BookingExtension previous = existing.get();
                if (reservationId.equals(previous.getReservation().getId())
                        && actor.equals(previous.getRequestedByUserId())
                        && requestedEnd.equals(previous.getRequestedEndAt())) {
                    return response(previous);
                }
                throw idempotencyConflict();
            }
        }
        if (reservation.getStatus() != rw.rra.roomiq.booking.domain.enums.ReservationStatus.CONFIRMED
                && reservation.getStatus() != rw.rra.roomiq.booking.domain.enums.ReservationStatus.IN_PROGRESS) {
            throw extensionStateConflict();
        }
        if (extensions.existsByReservation_IdAndStatus(reservationId, PENDING)) {
            throw extensionStateConflict();
        }

        ValidatedBookingReferences references = validateOwnerData(reservation, requestedEnd);
        if (references.releaseBufferMinutes() < 0) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "ROOM_BOOKING_POLICY_UNAVAILABLE",
                    "The current room release-buffer policy is unavailable");
        }
        BookingExtension extension = new BookingExtension(reservation, actor, reservation.getEndAt(),
                requestedEnd, null, PENDING, null, null, idempotencyKey, clock.instant(), null);
        try {
            BookingExtension saved = extensions.saveAndFlush(extension);
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("requesterUserId", saved.getRequestedByUserId().toString());
            facts.put("reservationId", reservation.getId().toString());
            facts.put("requestedEndAt", saved.getRequestedEndAt().toString());
            facts.put("notificationRoute", "SCOPED_ADMIN_REQUEST");
            domainEvents.record(2, "ExtensionRequested", "BOOKING_EXTENSION", saved.getId(), 0,
                    saved.getRequestedAt(), List.of(), facts);
            return response(saved);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, EXTENSION_KEY_CONSTRAINT)) {
                throw idempotencyConflict();
            }
            throw exception;
        }
    }

    @Transactional
    public BookingExtensionResponse decide(UUID extensionId, BookingExtensionDecisionRequest request) {
        if (extensionId == null || request == null || request.decision() == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "BOOKING_EXTENSION_DECISION_INVALID",
                    "An extension identifier and decision are required");
        }
        UUID reservationId = extensions.findReservationIdById(extensionId)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "BOOKING_EXTENSION_NOT_FOUND",
                        "Booking extension was not found"));
        Reservation reservation = lockReservation(reservationId);
        BookingExtension extension = lockExtension(extensionId);
        if (!reservation.getId().equals(extension.getReservation().getId())) {
            throw extensionStateConflict();
        }
        if (reservation.getRecurrenceRuleId() != null
                || reservation.getBookingRequest().getRecurrenceRuleId() != null) {
            throw recurringExtensionUnsupported();
        }
        UUID actor = authorizeDecision(extension, reservation);
        if (extension.getStatus() != PENDING) {
            throw extensionStateConflict();
        }
        if (request.decision() == ApprovalDecisionType.REJECTED) {
            if (!extension.decide(BookingExtensionStatus.REJECTED, actor,
                    trimToNull(request.comment()), timestamp(), null)) {
                throw extensionStateConflict();
            }
            BookingExtension saved = extensions.saveAndFlush(extension);
            recordDecisionEvent(saved, actor);
            return response(saved);
        }
        if (request.decision() != APPROVED) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "BOOKING_EXTENSION_DECISION_INVALID",
                    "The extension decision must be APPROVED or REJECTED");
        }
        if (!extension.getPreviousEndAt().equals(reservation.getEndAt())) {
            throw new DomainException(HttpStatus.CONFLICT, "BOOKING_EXTENSION_STALE",
                    "The reservation end changed after this extension was requested");
        }

        Instant decidedAt = timestamp();
        ValidatedBookingReferences references = validateOwnerData(reservation, extension.getRequestedEndAt());
        if (references.releaseBufferMinutes() < 0 || references.occurrences().size() != 1
                || !extension.getRequestedEndAt().equals(references.occurrences().getFirst().endsAt())
                || !reservation.getStartAt().equals(references.occurrences().getFirst().startsAt())) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "SCHEDULING_SERVICE_UNAVAILABLE",
                    "Current Scheduling and Room data did not validate a single extension interval");
        }
        Instant occupiedUntil = occupiedUntil(extension.getRequestedEndAt(), references.releaseBufferMinutes());
        List<ReservationOccurrence> reservationOccurrences =
                occurrences.findAllByReservationIdForUpdate(reservation.getId());
        if (reservationOccurrences.size() != 1 || !reservationOccurrences.getFirst().isOccupancyActive()
                || !extension.getPreviousEndAt().equals(reservationOccurrences.getFirst().getEndAt())) {
            throw extensionStateConflict();
        }
        if (reservations.existsRoomOccupancyConflictExcludingReservation(reservation.getRoomId(),
                reservation.getId(), reservation.getStartAt(), occupiedUntil)
                || occurrences.existsRoomOccupancyConflictExcludingReservation(reservation.getRoomId(),
                reservation.getId(), reservation.getStartAt(), occupiedUntil)) {
            throw occupancyConflict();
        }

        PGobject occupiedPeriod = occupiedPeriod(reservation.getStartAt(), occupiedUntil);
        if (!reservation.extendTo(extension.getRequestedEndAt(), references.releaseBufferMinutes(), occupiedPeriod)
                || !reservationOccurrences.getFirst().extendTo(extension.getRequestedEndAt(), occupiedPeriod)
                || !extension.decide(BookingExtensionStatus.APPROVED, actor, trimToNull(request.comment()), decidedAt,
                extension.getRequestedEndAt())) {
            throw extensionStateConflict();
        }
        try {
            reservations.saveAndFlush(reservation);
            occurrences.saveAndFlush(reservationOccurrences.getFirst());
            occupancyOutbox.recordUpserts(reservation, reservationOccurrences, decidedAt);
            BookingExtension saved = extensions.saveAndFlush(extension);
            recordDecisionEvent(saved, actor);
            return response(saved);
        } catch (DataAccessException exception) {
            if (hasConstraint(exception, RESERVATION_OVERLAP_CONSTRAINT)
                    || hasConstraint(exception, OCCURRENCE_OVERLAP_CONSTRAINT)
                    || isPostgresDeadlock(exception)) {
                throw occupancyConflict();
            }
            throw exception;
        }
    }

    @Transactional
    public BookingExtensionResponse cancel(UUID extensionId) {
        if (extensionId == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "BOOKING_EXTENSION_ID_INVALID",
                    "A booking extension identifier is required");
        }
        UUID reservationId = extensions.findReservationIdById(extensionId)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "BOOKING_EXTENSION_NOT_FOUND",
                        "Booking extension was not found"));
        Reservation reservation = lockReservation(reservationId);
        BookingExtension extension = lockExtension(extensionId);
        UUID actor = authorizeRequester(reservation);
        if (extension.getStatus() != PENDING || !actor.equals(extension.getRequestedByUserId())) {
            throw extensionStateConflict();
        }
        if (!extension.decide(CANCELLED, actor, null, timestamp(), null)) {
            throw extensionStateConflict();
        }
        BookingExtension saved = extensions.saveAndFlush(extension);
        recordDecisionEvent(saved, actor);
        return response(saved);
    }

    private void recordDecisionEvent(BookingExtension extension, UUID actor) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("requesterUserId", extension.getRequestedByUserId().toString());
        facts.put("decidedByUserId", actor.toString());
        facts.put("reservationId", extension.getReservation().getId().toString());
        facts.put("decision", extension.getStatus().name());
        if (extension.getDecisionComment() != null) {
            facts.put("decisionComment", extension.getDecisionComment());
        }
        domainEvents.record("ExtensionDecided", "BOOKING_EXTENSION", extension.getId(), 0,
                extension.getDecidedAt(), trustedRecipients(extension.getRequestedByUserId()), facts);
    }

    private static List<UUID> trustedRecipients(UUID... recipients) {
        List<UUID> unique = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (UUID recipient : recipients) {
            if (recipient != null && seen.add(recipient)) {
                unique.add(recipient);
            }
        }
        return List.copyOf(unique);
    }

    private UUID authorizeRequester(Reservation reservation) {
        BookingRequest bookingRequest = reservation.getBookingRequest();
        if (!bookingRequest.getRequestedByUserId().equals(reservation.getOrganizerUserId())) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_OWNER_DATA_INCONSISTENT",
                    "Stored reservation organizer does not match its booking requester");
        }
        UUID actor = authorizationClient.authorizeCurrentCaller(new BookingAuthorizationRequest(
                BookingAuthorizationRequest.Action.EXTENSION_REQUEST, bookingRequest.getOfficeBuildingId(),
                null, bookingRequest.getRequestedByUserId(), null));
        if (actor == null || !actor.equals(bookingRequest.getRequestedByUserId())
                || !actor.equals(reservation.getOrganizerUserId())) {
            throw new DomainException(HttpStatus.FORBIDDEN, "BOOKING_EXTENSION_REQUEST_DENIED",
                    "Only the reservation organizer with current extension permission may request an extension");
        }
        return actor;
    }

    private UUID authorizeDecision(BookingExtension extension, Reservation reservation) {
        BookingRequest bookingRequest = reservation.getBookingRequest();
        if (!bookingRequest.getRequestedByUserId().equals(reservation.getOrganizerUserId())
                || !bookingRequest.getRequestedByUserId().equals(extension.getRequestedByUserId())) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_OWNER_DATA_INCONSISTENT",
                    "Stored extension requester does not match the reservation organizer");
        }
        UUID actor = authorizationClient.authorizeCurrentCaller(new BookingAuthorizationRequest(
                BookingAuthorizationRequest.Action.EXTENSION_DECIDE, bookingRequest.getOfficeBuildingId(),
                null, extension.getRequestedByUserId(), null));
        if (actor == null || actor.equals(extension.getRequestedByUserId())) {
            throw new DomainException(HttpStatus.FORBIDDEN, "BOOKING_EXTENSION_SELF_DECISION_DENIED",
                    "An extension requester cannot decide their own extension request");
        }
        return actor;
    }

    private ValidatedBookingReferences validateOwnerData(Reservation reservation, Instant requestedEnd) {
        BookingRequest bookingRequest = reservation.getBookingRequest();
        ValidatedBookingReferences references = ownerServicesClient.validateExtension(
                new BookingRequestFacts(bookingRequest.getDepartmentId(), reservation.getRoomId(),
                        bookingRequest.getOfficeBuildingId(), null, reservation.getStartAt(), requestedEnd,
                        bookingRequest.getAttendeeCount(), bookingRequest.getExternalGuests()),
                BookingRequestService.currentBearerToken(), clock.instant());
        if (!bookingRequest.getOfficeBuildingId().equals(references.officeBuildingId())
                || !bookingRequest.getDepartmentId().equals(references.departmentId())
                || !reservation.getRoomId().equals(references.roomId())
                || references.occurrences() == null || references.occurrences().size() != 1) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_OWNER_DATA_INCONSISTENT",
                    "Current owner-service data does not match the stored reservation");
        }
        return references;
    }

    private Reservation lockReservation(UUID reservationId) {
        if (reservationId == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "RESERVATION_ID_INVALID",
                    "A reservation identifier is required");
        }
        return reservations.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND",
                        "Reservation was not found"));
    }

    private BookingExtension lockExtension(UUID extensionId) {
        BookingExtension extension = extensions.findByIdForUpdate(extensionId)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "BOOKING_EXTENSION_NOT_FOUND",
                        "Booking extension was not found"));
        entityManager.refresh(extension, LockModeType.PESSIMISTIC_WRITE);
        return extension;
    }

    private static void validateRequest(UUID reservationId, CreateBookingExtensionRequest request,
                                        String idempotencyKey) {
        if (reservationId == null || request == null || request.requestedEndAt() == null) {
            throw invalidExtension();
        }
        if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must contain 1-128 ASCII letters, digits, dots, underscores, colons, or hyphens");
        }
    }

    private static Instant normalized(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private Instant timestamp() {
        return normalized(clock.instant());
    }

    private static Instant occupiedUntil(Instant endAt, int releaseBufferMinutes) {
        try {
            return endAt.plusSeconds(Math.multiplyExact((long) releaseBufferMinutes, 60));
        } catch (ArithmeticException | java.time.DateTimeException exception) {
            throw invalidExtension();
        }
    }

    private static PGobject occupiedPeriod(Instant startsAt, Instant occupiedUntil) {
        PGobject range = new PGobject();
        range.setType("tstzrange");
        try {
            range.setValue("[" + startsAt + "," + occupiedUntil + ")");
            return range;
        } catch (SQLException exception) {
            throw new IllegalStateException("Validated extension interval could not be represented as tstzrange",
                    exception);
        }
    }

    private static BookingExtensionResponse response(BookingExtension extension) {
        return new BookingExtensionResponse(extension.getId(), extension.getReservation().getId(),
                extension.getRequestedByUserId(), extension.getPreviousEndAt(), extension.getRequestedEndAt(),
                extension.getApprovedEndAt(), extension.getStatus(), extension.getDecidedByUserId(),
                extension.getDecisionComment(), extension.getRequestedAt(), extension.getDecidedAt());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static DomainException invalidExtension() {
        return new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "BOOKING_EXTENSION_INVALID",
                "The requested end must be later than the current reservation end");
    }

    private static DomainException recurringExtensionUnsupported() {
        return new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "RECURRING_RESERVATION_EXTENSION_UNSUPPORTED",
                "Recurring reservations cannot be extended");
    }

    private static DomainException extensionStateConflict() {
        return new DomainException(HttpStatus.CONFLICT, "BOOKING_EXTENSION_STATE_CONFLICT",
                "The extension is stale, already has a decision, or conflicts with another pending extension");
    }

    private static DomainException idempotencyConflict() {
        return new DomainException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT",
                "Idempotency key has already been used for a different booking extension");
    }

    private static DomainException occupancyConflict() {
        return new DomainException(HttpStatus.CONFLICT, "ROOM_OCCUPANCY_CONFLICT",
                "The extended reservation conflicts with another room reservation");
    }

    private static boolean hasConstraint(Throwable exception, String expectedName) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && expectedName.equals(violation.getConstraintName())) {
                return true;
            }
            if (cause instanceof PSQLException postgresException
                    && postgresException.getServerErrorMessage() != null
                    && expectedName.equals(postgresException.getServerErrorMessage().getConstraint())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static boolean isPostgresDeadlock(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof PSQLException postgresException
                    && "40P01".equals(postgresException.getSQLState())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
