package rw.rra.roomiq.booking.domain.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.booking.domain.dto.CancellationRequest;
import rw.rra.roomiq.booking.domain.dto.CancellationResponse;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;
import rw.rra.roomiq.booking.domain.entity.Cancellation;
import rw.rra.roomiq.booking.domain.entity.Reservation;
import rw.rra.roomiq.booking.domain.entity.ReservationOccurrence;
import rw.rra.roomiq.booking.domain.enums.BookingRequestStatus;
import rw.rra.roomiq.booking.domain.enums.CancellationReason;
import rw.rra.roomiq.booking.domain.enums.ReservationStatus;
import rw.rra.roomiq.booking.domain.repository.BookingRequestRepository;
import rw.rra.roomiq.booking.domain.repository.CancellationRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationOccurrenceRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationRepository;
import rw.rra.roomiq.booking.integration.BookingAuthorizationClient;
import rw.rra.roomiq.booking.integration.BookingAuthorizationResponse;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient;
import rw.rra.roomiq.common.web.DomainException;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class BookingCancellationService {
    private final BookingRequestRepository bookingRequests;
    private final ReservationRepository reservations;
    private final ReservationOccurrenceRepository occurrences;
    private final CancellationRepository cancellations;
    private final BookingAuthorizationClient authorizationClient;
    private final BookingOwnerServicesClient ownerServicesClient;
    private final BookingOccupancyOutboxService occupancyOutbox;
    private final BookingDomainEventOutboxService domainEvents;
    private final Clock clock;

    @Autowired
    public BookingCancellationService(BookingRequestRepository bookingRequests,
                                     ReservationRepository reservations,
                                     ReservationOccurrenceRepository occurrences,
                                     CancellationRepository cancellations,
                                     BookingAuthorizationClient authorizationClient,
                                     BookingOwnerServicesClient ownerServicesClient,
                                     BookingOccupancyOutboxService occupancyOutbox,
                                     BookingDomainEventOutboxService domainEvents) {
        this(bookingRequests, reservations, occurrences, cancellations, authorizationClient,
                ownerServicesClient, occupancyOutbox, domainEvents, Clock.systemUTC());
    }

    BookingCancellationService(BookingRequestRepository bookingRequests,
                               ReservationRepository reservations,
                               ReservationOccurrenceRepository occurrences,
                               CancellationRepository cancellations,
                               BookingAuthorizationClient authorizationClient,
                               BookingOwnerServicesClient ownerServicesClient,
                               BookingOccupancyOutboxService occupancyOutbox,
                               BookingDomainEventOutboxService domainEvents, Clock clock) {
        this.bookingRequests = bookingRequests;
        this.reservations = reservations;
        this.occurrences = occurrences;
        this.cancellations = cancellations;
        this.authorizationClient = authorizationClient;
        this.ownerServicesClient = ownerServicesClient;
        this.occupancyOutbox = occupancyOutbox;
        this.domainEvents = domainEvents;
        this.clock = clock;
    }

    @Transactional
    public CancellationResponse cancelRequest(UUID requestId, CancellationRequest request) {
        validate(requestId, request);
        BookingRequest bookingRequest = bookingRequests.findByIdForUpdate(requestId)
                .orElseThrow(() -> notFound("BOOKING_REQUEST_NOT_FOUND", "Booking request was not found"));
        BookingAuthorizationResponse authorization = authorizationClient.authorizeCancellation(
                bookingRequest.getRequestedByUserId(), bookingRequest.getOfficeBuildingId());
        Cancellation prior = cancellations.findFirstByBookingRequest_IdOrderByCancelledAtDesc(requestId)
                .orElse(null);
        if (prior != null) {
            requireEquivalentReplay(prior, authorization.actorUserId(), request);
            return response(prior);
        }
        if (normalized(request.overrideReason()) != null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "CANCELLATION_OVERRIDE_NOT_APPLICABLE",
                    "An override explanation is not applicable to a booking request without a reservation");
        }
        if (reservations.existsByBookingRequest_Id(requestId)) {
            throw stateConflict("Cancel the confirmed reservation through the reservation cancellation operation");
        }
        if (bookingRequest.getStatus() != BookingRequestStatus.DRAFT
                && bookingRequest.getStatus() != BookingRequestStatus.PENDING_APPROVAL
                && bookingRequest.getStatus() != BookingRequestStatus.APPROVED) {
            throw stateConflict("The booking request cannot be cancelled in its current state");
        }
        validateReason(request, authorization.cancellationAuthority());
        Instant now = timestamp();
        if (!bookingRequest.cancel()) {
            throw stateConflict("The booking request cannot be cancelled in its current state");
        }
        Cancellation cancellation = cancellations.saveAndFlush(new Cancellation(null, bookingRequest,
                authorization.actorUserId(), request.reasonCode(), request.reason().trim(), null,
                false, null, now));
        bookingRequests.saveAndFlush(bookingRequest);
        domainEvents.record("BookingCancelled", "CANCELLATION", cancellation.getId(), 0, now,
                trustedRecipients(bookingRequest.getRequestedByUserId(), authorization.actorUserId()), Map.of(
                        "cancelledByUserId", authorization.actorUserId().toString(),
                        "reasonCode", request.reasonCode().name(),
                        "bookingRequestId", bookingRequest.getId().toString()));
        return response(cancellation);
    }

    @Transactional
    public CancellationResponse cancelReservation(UUID reservationId, CancellationRequest request) {
        validate(reservationId, request);
        Reservation reservation = reservations.findByIdForUpdate(reservationId)
                .orElseThrow(() -> notFound("RESERVATION_NOT_FOUND", "Reservation was not found"));
        BookingRequest bookingRequest = bookingRequests.findByIdForUpdate(reservation.getBookingRequest().getId())
                .orElseThrow(() -> notFound("BOOKING_REQUEST_NOT_FOUND", "Booking request was not found"));
        BookingAuthorizationResponse authorization = authorizationClient.authorizeCancellation(
                reservation.getOrganizerUserId(), bookingRequest.getOfficeBuildingId());
        Cancellation prior = cancellations.findFirstByReservation_IdOrderByCancelledAtDesc(reservationId)
                .orElse(null);
        if (prior != null) {
            requireEquivalentReplay(prior, authorization.actorUserId(), request);
            return response(prior);
        }
        if (reservation.getStatus() != ReservationStatus.CONFIRMED
                && reservation.getStatus() != ReservationStatus.IN_PROGRESS) {
            throw stateConflict("The reservation cannot be cancelled in its current state");
        }
        if (bookingRequest.getStatus() != BookingRequestStatus.APPROVED) {
            throw stateConflict("Reservation and booking request states are inconsistent");
        }
        validateReason(request, authorization.cancellationAuthority());
        Instant deadline = ownerServicesClient.cancellationDeadlineAt(reservation.getRoomId(),
                bookingRequest.getOfficeBuildingId(), reservation.getStartAt(),
                authorizationClient.currentBearerToken());
        Instant now = timestamp();
        String overrideReason = normalized(request.overrideReason());
        boolean overdue = deadline != null && now.isAfter(deadline);
        if (overdue && (!isAdministrator(authorization.cancellationAuthority())
                || overrideReason == null)) {
            throw new DomainException(HttpStatus.FORBIDDEN, "CANCELLATION_DEADLINE_EXCEEDED",
                    "The cancellation deadline has passed; an authorized administrator override with an explanation is required");
        }
        if (!overdue && overrideReason != null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "CANCELLATION_OVERRIDE_NOT_REQUIRED",
                    "An override explanation is only accepted when the cancellation deadline has passed");
        }
        if (!reservation.cancel() || !bookingRequest.cancel()) {
            throw stateConflict("The reservation cannot be cancelled in its current state");
        }
        List<ReservationOccurrence> reservationOccurrences =
                occurrences.findAllByReservationIdForUpdate(reservationId);
        reservations.saveAndFlush(reservation);
        bookingRequests.saveAndFlush(bookingRequest);
        occupancyOutbox.recordRemovals(reservation, reservationOccurrences, now);
        reservationOccurrences.forEach(ReservationOccurrence::releaseOccupancy);
        occurrences.saveAllAndFlush(reservationOccurrences);
        Cancellation cancellation = cancellations.saveAndFlush(new Cancellation(reservation, bookingRequest,
                authorization.actorUserId(), request.reasonCode(), request.reason().trim(), deadline,
                overdue, overdue ? overrideReason : null, now));
        domainEvents.record("BookingCancelled", "CANCELLATION", cancellation.getId(), 0, now,
                trustedRecipients(bookingRequest.getRequestedByUserId(), reservation.getOrganizerUserId(),
                        authorization.actorUserId()), Map.of(
                        "cancelledByUserId", authorization.actorUserId().toString(),
                        "reasonCode", request.reasonCode().name(),
                        "reservationId", reservation.getId().toString()));
        return response(cancellation);
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

    private static void validate(UUID resourceId, CancellationRequest request) {
        if (resourceId == null || request == null || request.reasonCode() == null
                || request.reason() == null || request.reason().isBlank()
                || request.reason().trim().length() > 2000
                || request.overrideReason() != null && request.overrideReason().trim().length() > 1000) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "CANCELLATION_REQUEST_INVALID",
                    "A valid cancellation reason is required");
        }
    }

    private static void validateReason(CancellationRequest request,
                                       BookingAuthorizationResponse.CancellationAuthority authority) {
        if (authority == BookingAuthorizationResponse.CancellationAuthority.OWNER
                && request.reasonCode() != CancellationReason.USER
                || authority != BookingAuthorizationResponse.CancellationAuthority.OWNER
                && request.reasonCode() == CancellationReason.USER) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "CANCELLATION_REASON_INVALID",
                    "The cancellation reason code is not valid for this actor");
        }
    }

    private static boolean isAdministrator(BookingAuthorizationResponse.CancellationAuthority authority) {
        return authority == BookingAuthorizationResponse.CancellationAuthority.ADMIN
                || authority == BookingAuthorizationResponse.CancellationAuthority.SYSTEM_ADMIN;
    }

    private Instant timestamp() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireEquivalentReplay(Cancellation prior, UUID actorUserId,
                                                CancellationRequest request) {
        if (!prior.getCancelledByUserId().equals(actorUserId)
                || prior.getReasonCode() != request.reasonCode()
                || !prior.getReason().equals(request.reason().trim())
                || !Objects.equals(prior.getOverrideReason(), normalized(request.overrideReason()))) {
            throw new DomainException(HttpStatus.CONFLICT, "CANCELLATION_REPLAY_CONFLICT",
                    "The cancellation was already recorded with a different actor or payload");
        }
    }

    private static CancellationResponse response(Cancellation cancellation) {
        Reservation reservation = cancellation.getReservation();
        BookingRequest request = cancellation.getBookingRequest();
        return new CancellationResponse(cancellation.getId(),
                reservation == null ? null : reservation.getId(),
                request == null ? null : request.getId(),
                cancellation.getCancelledByUserId(), cancellation.getReasonCode(), cancellation.getReason(),
                cancellation.getCancellationDeadlineAt(), cancellation.isOverrideApplied(),
                cancellation.getOverrideReason(), cancellation.getCancelledAt(),
                reservation == null ? null : reservation.getStatus(),
                request == null ? null : request.getStatus());
    }

    private static DomainException stateConflict(String message) {
        return new DomainException(HttpStatus.CONFLICT, "CANCELLATION_STATE_CONFLICT", message);
    }

    private static DomainException notFound(String code, String message) {
        return new DomainException(HttpStatus.NOT_FOUND, code, message);
    }
}
