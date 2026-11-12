package rw.rra.roomiq.booking.domain.service;

import jakarta.servlet.http.HttpServletRequest;
import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;
import rw.rra.roomiq.booking.domain.entity.Meeting;
import rw.rra.roomiq.booking.domain.entity.MeetingParticipant;
import rw.rra.roomiq.booking.domain.entity.Reservation;
import rw.rra.roomiq.booking.domain.entity.ReservationOccurrence;
import rw.rra.roomiq.booking.domain.enums.BookingRequestStatus;
import rw.rra.roomiq.booking.domain.enums.InviteStatus;
import rw.rra.roomiq.booking.domain.enums.MeetingVisibility;
import rw.rra.roomiq.booking.domain.enums.ParticipantRole;
import rw.rra.roomiq.booking.domain.enums.ReservationStatus;
import rw.rra.roomiq.booking.domain.repository.BookingRequestRepository;
import rw.rra.roomiq.booking.domain.repository.MeetingParticipantRepository;
import rw.rra.roomiq.booking.domain.repository.MeetingRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationOccurrenceRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationRepository;
import rw.rra.roomiq.booking.integration.BookingAuthorizationResponse;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient.BookingRequestFacts;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient.SchedulingOccurrence;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient.ValidatedBookingReferences;
import rw.rra.roomiq.common.web.DomainException;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class BookingConfirmationService {
    private static final String RESERVATION_OVERLAP_CONSTRAINT = "ex_reservation_room_occupied_period";
    private static final String OCCURRENCE_OVERLAP_CONSTRAINT =
            "ex_reservation_occurrence_room_occupied_period";

    private final BookingRequestRepository bookingRequests;
    private final ReservationRepository reservations;
    private final ReservationOccurrenceRepository occurrences;
    private final MeetingRepository meetings;
    private final MeetingParticipantRepository participants;
    private final BookingOwnerServicesClient ownerServicesClient;
    private final BookingOccupancyOutboxService occupancyOutbox;
    private final BookingDomainEventOutboxService domainEventOutbox;
    private final BookingSourceEvidenceOutboxService sourceEvidenceOutbox;

    @Autowired
    public BookingConfirmationService(BookingRequestRepository bookingRequests,
                                      ReservationRepository reservations,
                                      ReservationOccurrenceRepository occurrences,
                                      MeetingRepository meetings,
                                      MeetingParticipantRepository participants,
                                      BookingOwnerServicesClient ownerServicesClient,
                                      BookingOccupancyOutboxService occupancyOutbox,
                                      BookingDomainEventOutboxService domainEventOutbox,
                                      BookingSourceEvidenceOutboxService sourceEvidenceOutbox) {
        this.bookingRequests = bookingRequests;
        this.reservations = reservations;
        this.occurrences = occurrences;
        this.meetings = meetings;
        this.participants = participants;
        this.ownerServicesClient = ownerServicesClient;
        this.occupancyOutbox = occupancyOutbox;
        this.domainEventOutbox = domainEventOutbox;
        this.sourceEvidenceOutbox = sourceEvidenceOutbox;
    }

    @Transactional
    public ConfirmedBooking confirm(BookingRequest bookingRequest,
                                   BookingAuthorizationResponse authorization,
                                   Instant confirmedAt) {
        if (bookingRequest == null || authorization == null || confirmedAt == null
                || !bookingRequest.getRequestedByUserId().equals(authorization.resourceOwnerUserId())
                || authorization.resourceOwnerDisplayName() == null
                || authorization.resourceOwnerDisplayName().isBlank()) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_CONTENT, "BOOKING_REQUESTER_IDENTITY_INVALID",
                    "The current requester identity is unavailable or inconsistent");
        }

        ValidatedBookingReferences references = ownerServicesClient.validateRequest(
                new BookingRequestFacts(bookingRequest.getDepartmentId(), bookingRequest.getRoomId(),
                        bookingRequest.getOfficeBuildingId(), bookingRequest.getRecurrenceRuleId(),
                        bookingRequest.getRequestedStart(), bookingRequest.getRequestedEnd(),
                        bookingRequest.getAttendeeCount(), bookingRequest.getExternalGuests()),
                currentBearerToken(), confirmedAt);
        if (!bookingRequest.getOfficeBuildingId().equals(references.officeBuildingId())
                || !bookingRequest.getDepartmentId().equals(references.departmentId())
                || !bookingRequest.getRoomId().equals(references.roomId())) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_OWNER_DATA_INCONSISTENT",
                    "Current owner-service data does not match the stored booking request");
        }
        if (references.releaseBufferMinutes() < 0 || references.occurrences() == null
                || references.occurrences().isEmpty()) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "ROOM_BOOKING_POLICY_UNAVAILABLE",
                    "The current room booking policy or validated occurrences are unavailable");
        }

        List<OccurrenceRange> ranges = occurrenceRanges(references, confirmedAt);
        checkExistingConflicts(bookingRequest.getRoomId(), ranges);
        if (!bookingRequest.approve()) {
            throw new DomainException(HttpStatus.CONFLICT, "BOOKING_REQUEST_STATE_CONFLICT",
                    "Only a pending booking request can be confirmed");
        }

        SchedulingOccurrence first = references.occurrences().getFirst();
        Reservation reservation = new Reservation(bookingRequest, bookingRequest.getRoomId(),
                bookingRequest.getRequestedByUserId(), bookingRequest.getRecurrenceRuleId(),
                occupiedPeriod(first.startsAt(), ranges.getFirst().occupiedUntil()),
                first.startsAt(), first.endsAt(), references.releaseBufferMinutes(),
                ReservationStatus.CONFIRMED, confirmedAt);
        Meeting meeting = new Meeting(reservation, bookingRequest.getTitle(), bookingRequest.getPurpose(),
                authorization.resourceOwnerDisplayName(), null, null, MeetingVisibility.PRIVATE, confirmedAt);
        MeetingParticipant organizer = new MeetingParticipant(meeting, bookingRequest.getRequestedByUserId(), null,
                authorization.resourceOwnerDisplayName(), ParticipantRole.ORGANIZER, InviteStatus.ACCEPTED);

        List<ReservationOccurrence> reservationOccurrences = new ArrayList<>(ranges.size());
        for (OccurrenceRange range : ranges) {
            reservationOccurrences.add(new ReservationOccurrence(reservation, bookingRequest.getRoomId(),
                    range.occurrence().startsAt(), range.occurrence().endsAt(),
                    occupiedPeriod(range.occurrence().startsAt(), range.occupiedUntil())));
        }

        try {
            reservations.saveAndFlush(reservation);
        } catch (DataAccessException exception) {
            if (hasConstraint(exception, RESERVATION_OVERLAP_CONSTRAINT) || isPostgresDeadlock(exception)) {
                throw occupancyConflict(List.of(first.occurrenceDate()));
            }
            throw exception;
        }
        for (int index = 0; index < reservationOccurrences.size(); index++) {
            try {
                occurrences.saveAndFlush(reservationOccurrences.get(index));
            } catch (DataAccessException exception) {
                if (hasConstraint(exception, OCCURRENCE_OVERLAP_CONSTRAINT) || isPostgresDeadlock(exception)) {
                    throw occupancyConflict(List.of(ranges.get(index).occurrence().occurrenceDate()));
                }
                throw exception;
            }
        }
        occupancyOutbox.recordUpserts(reservation, reservationOccurrences, confirmedAt);
        meetings.saveAndFlush(meeting);
        participants.saveAndFlush(organizer);
        bookingRequests.saveAndFlush(bookingRequest);
        sourceEvidenceOutbox.record(bookingRequest, confirmedAt, confirmedAt);
        recordMeetingReminders(meeting, reservation, reservationOccurrences,
                bookingRequest.getRequestedByUserId(), references.timezone(), confirmedAt);
        return new ConfirmedBooking(reservation, meeting, reservationOccurrences.size());
    }

    private void recordMeetingReminders(Meeting meeting, Reservation reservation,
                                        List<ReservationOccurrence> reservationOccurrences,
                                        UUID organizerUserId, String timezone, Instant confirmedAt) {
        for (ReservationOccurrence occurrence : reservationOccurrences) {
            for (int offsetMinutes : List.of(15, 5)) {
                Instant reminderAt = occurrence.getStartAt().minus(Duration.ofMinutes(offsetMinutes));
                domainEventOutbox.record("Reminder", "MEETING", meeting.getId(), 1, confirmedAt,
                        List.of(organizerUserId), Map.of(
                                "meetingId", meeting.getId().toString(),
                                "reservationId", reservation.getId().toString(),
                                "occurrenceStartsAt", occurrence.getStartAt().toString(),
                                "timezone", timezone,
                                "reminderAt", reminderAt.toString(),
                                "reminderOffsetMinutes", offsetMinutes));
            }
        }
    }

    private void checkExistingConflicts(UUID roomId, List<OccurrenceRange> ranges) {
        TreeSet<java.time.LocalDate> conflictingDates = new TreeSet<>();
        List<OccurrenceRange> precedingRanges = new ArrayList<>();
        for (OccurrenceRange range : ranges) {
            SchedulingOccurrence occurrence = range.occurrence();
            for (OccurrenceRange previous : precedingRanges) {
                if (occurrence.startsAt().isBefore(previous.occupiedUntil())) {
                    conflictingDates.add(previous.occurrence().occurrenceDate());
                    conflictingDates.add(occurrence.occurrenceDate());
                }
            }
            if (occurrences.existsRoomOccupancyConflict(roomId, occurrence.startsAt(), range.occupiedUntil())) {
                conflictingDates.add(occurrence.occurrenceDate());
            }
            precedingRanges.add(range);
        }
        if (!conflictingDates.isEmpty()) {
            throw occupancyConflict(conflictingDates);
        }
    }

    private static List<OccurrenceRange> occurrenceRanges(ValidatedBookingReferences references, Instant now) {
        List<SchedulingOccurrence> validated = references.occurrences();
        List<OccurrenceRange> ranges = new ArrayList<>(validated.size());
        SchedulingOccurrence previous = null;
        for (SchedulingOccurrence occurrence : validated) {
            if (occurrence == null || occurrence.occurrenceDate() == null || occurrence.startsAt() == null
                    || occurrence.endsAt() == null || !occurrence.endsAt().isAfter(occurrence.startsAt())
                    || previous != null && (!occurrence.occurrenceDate().isAfter(previous.occurrenceDate())
                    || !occurrence.startsAt().isAfter(previous.startsAt()))) {
                throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "SCHEDULING_SERVICE_UNAVAILABLE",
                        "Scheduling returned invalid or unordered occurrence intervals");
            }
            Instant occupiedUntil;
            try {
                occupiedUntil = occurrence.endsAt().plusSeconds(
                        Math.multiplyExact((long) references.releaseBufferMinutes(), 60));
            } catch (ArithmeticException | java.time.DateTimeException exception) {
                throw new DomainException(HttpStatus.UNPROCESSABLE_CONTENT, "BOOKING_INTERVAL_INVALID",
                        "The requested occupied interval exceeds the supported time range");
            }
            ranges.add(new OccurrenceRange(occurrence, occupiedUntil));
            previous = occurrence;
        }
        if (ranges.size() > 365 || ranges.getFirst().occurrence().startsAt().isBefore(now)) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_CONTENT, "BOOKING_OCCURRENCE_LIMIT_INVALID",
                    "The validated booking occurrence set is outside the supported bounds");
        }
        return List.copyOf(ranges);
    }

    private static PGobject occupiedPeriod(Instant startsAt, Instant occupiedUntil) {
        PGobject range = new PGobject();
        range.setType("tstzrange");
        try {
            range.setValue("[" + startsAt + "," + occupiedUntil + ")");
        } catch (SQLException exception) {
            throw new IllegalStateException("Validated booking interval could not be represented as tstzrange",
                    exception);
        }
        return range;
    }

    private static String currentBearerToken() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                    && !authorization.substring(7).isBlank()) {
                return authorization;
            }
        }
        throw new DomainException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                "Authentication is required");
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

    private static DomainException occupancyConflict(Collection<java.time.LocalDate> occurrenceDates) {
        String detail = "Room occupancy conflicts on occurrence dates: " + occurrenceDates.stream()
                .map(java.time.LocalDate::toString).distinct().sorted().collect(
                        java.util.stream.Collectors.joining(", "));
        return new DomainException(HttpStatus.CONFLICT, "ROOM_OCCUPANCY_CONFLICT", detail);
    }

    private record OccurrenceRange(SchedulingOccurrence occurrence, Instant occupiedUntil) { }

    public record ConfirmedBooking(Reservation reservation, Meeting meeting, int occurrenceCount) { }
}
