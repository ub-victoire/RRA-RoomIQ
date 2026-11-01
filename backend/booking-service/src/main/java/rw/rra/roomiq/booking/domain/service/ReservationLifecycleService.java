package rw.rra.roomiq.booking.domain.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.booking.domain.dto.ReservationLifecycleResponse;
import rw.rra.roomiq.booking.domain.entity.Reservation;
import rw.rra.roomiq.booking.domain.entity.ReservationOccurrence;
import rw.rra.roomiq.booking.domain.repository.MeetingParticipantRepository;
import rw.rra.roomiq.booking.domain.repository.MeetingRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationOccurrenceRepository;
import rw.rra.roomiq.booking.integration.BookingAuthorizationClient;
import rw.rra.roomiq.common.web.DomainException;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class ReservationLifecycleService {
    private final ReservationRepository reservations;
    private final ReservationOccurrenceRepository occurrences;
    private final BookingAuthorizationClient authorizationClient;
    private final MeetingRepository meetings;
    private final MeetingParticipantRepository participants;
    private final BookingOccupancyOutboxService occupancyOutbox;
    private final BookingDomainEventOutboxService domainEvents;
    private final Clock clock;

    @Autowired
    public ReservationLifecycleService(ReservationRepository reservations,
                                       ReservationOccurrenceRepository occurrences,
                                       BookingAuthorizationClient authorizationClient,
                                       MeetingRepository meetings,
                                       MeetingParticipantRepository participants,
                                       BookingOccupancyOutboxService occupancyOutbox,
                                       BookingDomainEventOutboxService domainEvents) {
        this(reservations, occurrences, authorizationClient, meetings, participants, occupancyOutbox,
                domainEvents, Clock.systemUTC());
    }

    ReservationLifecycleService(ReservationRepository reservations,
                                ReservationOccurrenceRepository occurrences,
                                BookingAuthorizationClient authorizationClient,
                                MeetingRepository meetings,
                                MeetingParticipantRepository participants,
                                BookingOccupancyOutboxService occupancyOutbox,
                                BookingDomainEventOutboxService domainEvents,
                                Clock clock) {
        this.reservations = reservations;
        this.occurrences = occurrences;
        this.authorizationClient = authorizationClient;
        this.meetings = meetings;
        this.participants = participants;
        this.occupancyOutbox = occupancyOutbox;
        this.domainEvents = domainEvents;
        this.clock = clock;
    }

    @Transactional
    public ReservationLifecycleResponse checkIn(UUID reservationId) {
        Reservation reservation = lockReservation(reservationId);
        authorize(reservation);
        if (!reservation.checkIn(serverTimestamp())) {
            throw invalidTransition();
        }
        return response(reservations.saveAndFlush(reservation));
    }

    @Transactional
    public ReservationLifecycleResponse complete(UUID reservationId) {
        Reservation reservation = lockReservation(reservationId);
        authorize(reservation);
        if (!reservation.complete(serverTimestamp())) {
            throw invalidTransition();
        }
        Reservation completed = reservations.saveAndFlush(reservation);
        var meeting = meetings.findByReservation_Id(completed.getId()).orElseThrow(() ->
                new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_MEETING_INCONSISTENT",
                        "A completed reservation has no persisted meeting"));
        List<UUID> recipients = Stream.concat(Stream.of(completed.getOrganizerUserId()),
                        participants.findAllByMeeting_IdOrderById(meeting.getId()).stream()
                                .map(participant -> participant.getUserId()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        domainEvents.record("MeetingCompleted", "MEETING", meeting.getId(), 0,
                completed.getCompletedAt(), recipients, Map.of(
                        "meetingId", meeting.getId().toString(),
                        "completedAt", completed.getCompletedAt().toString(),
                        "reservationId", completed.getId().toString()));
        return response(completed);
    }

    @Transactional
    public ReservationLifecycleResponse release(UUID reservationId) {
        Reservation reservation = lockReservation(reservationId);
        UUID actor = authorize(reservation);
        var meeting = meetings.findByReservation_Id(reservation.getId()).orElseThrow(() ->
                new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_MEETING_INCONSISTENT",
                        "A released reservation has no persisted meeting"));
        List<ReservationOccurrence> reservationOccurrences =
                occurrences.findAllByReservationIdForUpdate(reservation.getId());
        if (reservationOccurrences.isEmpty()) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_OCCUPANCY_INCONSISTENT",
                    "A released reservation has no persisted occupancy occurrences");
        }
        Instant releasedAt = serverTimestamp();
        if (!reservation.release()) {
            throw invalidTransition();
        }
        Reservation released = reservations.saveAndFlush(reservation);
        occupancyOutbox.recordRemovals(released, reservationOccurrences, releasedAt);
        reservationOccurrences.forEach(ReservationOccurrence::releaseOccupancy);
        occurrences.saveAllAndFlush(reservationOccurrences);
        List<UUID> recipients = Stream.concat(Stream.of(released.getOrganizerUserId()),
                        participants.findAllByMeeting_IdOrderById(meeting.getId()).stream()
                                .map(participant -> participant.getUserId()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        domainEvents.record("MeetingReleased", "MEETING", meeting.getId(), released.getVersion(),
                releasedAt, recipients, Map.of(
                        "meetingId", meeting.getId().toString(),
                        "releasedAt", releasedAt.toString(),
                        "reservationId", released.getId().toString(),
                        "releasedByUserId", actor.toString()));
        return response(released);
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

    private UUID authorize(Reservation reservation) {
        UUID actor = authorizationClient.authorizeReservationLifecycle(reservation.getOrganizerUserId(),
                reservation.getBookingRequest().getDepartmentId(),
                reservation.getBookingRequest().getOfficeBuildingId());
        if (actor == null) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_AUTHORIZATION_UNAVAILABLE",
                    "Identity authorization is unavailable; the reservation operation cannot proceed");
        }
        return actor;
    }

    private static ReservationLifecycleResponse response(Reservation reservation) {
        return new ReservationLifecycleResponse(reservation.getId(), reservation.getStatus(),
                reservation.getCheckedInAt(), reservation.getCompletedAt());
    }

    private Instant serverTimestamp() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static DomainException invalidTransition() {
        return new DomainException(HttpStatus.CONFLICT, "RESERVATION_STATE_CONFLICT",
                "The reservation is not in a state that permits this lifecycle transition");
    }
}
