package rw.rra.roomiq.booking.domain.service;

import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.booking.domain.dto.CreateBookingRequest;
import rw.rra.roomiq.booking.domain.dto.DirectBookingResponse;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;
import rw.rra.roomiq.booking.domain.entity.Meeting;
import rw.rra.roomiq.booking.domain.enums.BookingRequestStatus;
import rw.rra.roomiq.booking.domain.enums.BookingRequestType;
import rw.rra.roomiq.booking.domain.repository.BookingRequestRepository;
import rw.rra.roomiq.booking.domain.repository.MeetingRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationOccurrenceRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationRepository;
import rw.rra.roomiq.booking.integration.BookingAuthorizationClient;
import rw.rra.roomiq.booking.integration.BookingAuthorizationRequest;
import rw.rra.roomiq.booking.integration.BookingAuthorizationResponse;
import rw.rra.roomiq.booking.integration.BookingAuthorizationResponse.DirectBookingAuthority;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient.BookingRequestFacts;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient.ValidatedBookingReferences;
import rw.rra.roomiq.common.web.DomainException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.Map;

import static rw.rra.roomiq.booking.domain.enums.BookingRequestStatus.PENDING_APPROVAL;
import static rw.rra.roomiq.booking.integration.BookingAuthorizationRequest.Action.AUTHENTICATE;

@Service
public class BookingDirectBookingService {
    private static final String UNIQUE_IDEMPOTENCY_KEY_CONSTRAINT = "uq_booking_request_idempotency_key";

    private final BookingRequestRepository requests;
    private final ReservationRepository reservations;
    private final ReservationOccurrenceRepository occurrences;
    private final MeetingRepository meetings;
    private final BookingAuthorizationClient authorizationClient;
    private final BookingOwnerServicesClient ownerServicesClient;
    private final BookingConfirmationService confirmationService;
    private final PostgresIdempotencyLock idempotencyLock;
    private final BookingDomainEventOutboxService domainEvents;
    private final Clock clock;

    @Autowired
    public BookingDirectBookingService(BookingRequestRepository requests,
                                       ReservationRepository reservations,
                                       ReservationOccurrenceRepository occurrences,
                                       MeetingRepository meetings,
                                       BookingAuthorizationClient authorizationClient,
                                       BookingOwnerServicesClient ownerServicesClient,
                                       BookingConfirmationService confirmationService,
                                       PostgresIdempotencyLock idempotencyLock,
                                       BookingDomainEventOutboxService domainEvents) {
        this(requests, reservations, occurrences, meetings, authorizationClient, ownerServicesClient,
                confirmationService, idempotencyLock, domainEvents, Clock.systemUTC());
    }

    BookingDirectBookingService(BookingRequestRepository requests,
                                ReservationRepository reservations,
                                ReservationOccurrenceRepository occurrences,
                                MeetingRepository meetings,
                                BookingAuthorizationClient authorizationClient,
                                BookingOwnerServicesClient ownerServicesClient,
                                BookingConfirmationService confirmationService,
                                PostgresIdempotencyLock idempotencyLock,
                                BookingDomainEventOutboxService domainEvents,
                                Clock clock) {
        this.requests = requests;
        this.reservations = reservations;
        this.occurrences = occurrences;
        this.meetings = meetings;
        this.authorizationClient = authorizationClient;
        this.ownerServicesClient = ownerServicesClient;
        this.confirmationService = confirmationService;
        this.idempotencyLock = idempotencyLock;
        this.domainEvents = domainEvents;
        this.clock = clock;
    }

    @Transactional
    public DirectBookingResponse create(CreateBookingRequest request, String idempotencyKey) {
        BookingRequestService.validateIdempotencyKey(idempotencyKey);
        UUID authenticatedActor = authorizationClient.authorizeCurrentCaller(
                new BookingAuthorizationRequest(AUTHENTICATE, null, null, null, null));
        BookingAuthorizationResponse initialAuthorization = authorizationClient.authorizeDirectBooking(
                request.officeBuildingId(), request.departmentId(), false);
        requireConsistentActor(authenticatedActor, initialAuthorization);
        Instant now = clock.instant();
        ValidatedBookingReferences references = ownerServicesClient.validateRequest(
                new BookingRequestFacts(request.departmentId(), request.roomId(), request.officeBuildingId(),
                        request.recurrenceRuleId(), request.requestedStart(), request.requestedEnd(),
                        request.attendeeCount(), request.externalGuests()),
                BookingRequestService.currentBearerToken(), now);
        BookingAuthorizationResponse authorization = initialAuthorization;
        if (references.vipRoom()) {
            authorization = authorizationClient.authorizeDirectBooking(
                    references.officeBuildingId(), references.departmentId(), true);
            requireConsistentActor(authenticatedActor, authorization);
            if (authorization.directBookingAuthority() != initialAuthorization.directBookingAuthority()) {
                throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_AUTHORIZATION_INCONSISTENT",
                        "Identity returned inconsistent direct-booking authority");
            }
        }

        if (idempotencyKey != null) {
            idempotencyLock.acquire("booking-request:create", idempotencyKey);
            Optional<BookingRequest> existing = requests.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                BookingRequest prior = existing.get();
                if (authenticatedActor.equals(prior.getRequestedByUserId())
                        && BookingRequestService.matches(prior, request)) {
                    return replay(prior, references.occurrences().size());
                }
                throw idempotencyConflict();
            }
        }

        BookingRequestType requestType = authorization.directBookingAuthority() == DirectBookingAuthority.ADMIN
                ? BookingRequestType.ADMIN_DIRECT_BOOKING : BookingRequestType.SECRETARY_REQUEST;
        BookingRequest bookingRequest = new BookingRequest(
                "BR-" + UUID.randomUUID(),
                requestType,
                authorization.actorUserId(),
                references.departmentId(),
                references.roomId(),
                references.officeBuildingId(),
                request.recurrenceRuleId(),
                request.title().trim(),
                trimToNull(request.purpose()),
                request.requestedStart(),
                request.requestedEnd(),
                request.attendeeCount(),
                request.externalGuests(),
                PENDING_APPROVAL,
                idempotencyKey,
                now);

        try {
            requests.saveAndFlush(bookingRequest);
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            if (hasConstraint(exception, UNIQUE_IDEMPOTENCY_KEY_CONSTRAINT)) {
                throw idempotencyConflict();
            }
            throw exception;
        }

        domainEvents.record(2, "BookingRequested", "BOOKING_REQUEST", bookingRequest.getId(),
                bookingRequest.getVersion(), now, List.of(), Map.of(
                        "requesterUserId", bookingRequest.getRequestedByUserId().toString(),
                        "departmentId", bookingRequest.getDepartmentId().toString(),
                        "vipRoom", references.vipRoom(),
                        "notificationRoute", "NONE",
                        "roomId", bookingRequest.getRoomId().toString(),
                        "requestReference", bookingRequest.getRequestReference()));

        if (references.approvalRequired()) {
            return response(bookingRequest, null, null, references.occurrences().size());
        }

        BookingConfirmationService.ConfirmedBooking confirmed =
                confirmationService.confirm(bookingRequest, authorization, now);
        return response(bookingRequest, confirmed.reservation().getId(),
                confirmed.meeting().getId(), confirmed.occurrenceCount());
    }

    private DirectBookingResponse replay(BookingRequest request, int validatedOccurrenceCount) {
        var reservation = reservations.findByBookingRequest_Id(request.getId()).orElse(null);
        if (reservation == null) {
            if (request.getStatus() == BookingRequestStatus.APPROVED) {
                throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_RESERVATION_INCONSISTENT",
                        "An approved direct booking has no persisted reservation");
            }
            return response(request, null, null, validatedOccurrenceCount);
        }
        Meeting meeting = meetings.findByReservation_Id(reservation.getId()).orElseThrow(() ->
                new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_MEETING_INCONSISTENT",
                        "A confirmed direct booking has no persisted meeting"));
        int occurrenceCount = occurrences.findAllByReservation_IdOrderByStartAt(reservation.getId()).size();
        if (occurrenceCount == 0) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_OCCURRENCE_INCONSISTENT",
                    "A confirmed direct booking has no persisted occurrence intervals");
        }
        return response(request, reservation.getId(), meeting.getId(), occurrenceCount);
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

    private static DirectBookingResponse response(BookingRequest request, UUID reservationId,
                                                  UUID meetingId, int occurrenceCount) {
        return new DirectBookingResponse(request.getId(), request.getRequestReference(),
                request.getRequestType(), request.getStatus(), reservationId, meetingId, occurrenceCount);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static DomainException idempotencyConflict() {
        return new DomainException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT",
                "Idempotency key has already been used for a different booking request");
    }

    private static boolean hasConstraint(org.springframework.dao.DataIntegrityViolationException exception,
                                         String expectedName) {
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

    private static void requireConsistentActor(UUID authenticatedActor, BookingAuthorizationResponse authorization) {
        if (authorization == null || !authenticatedActor.equals(authorization.actorUserId())) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_AUTHORIZATION_INCONSISTENT",
                    "Identity returned inconsistent actors during direct-booking authorization");
        }
    }
}
