package rw.rra.roomiq.booking;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.postgresql.util.PGobject;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import rw.rra.roomiq.booking.domain.dto.BookingDecisionRequest;
import rw.rra.roomiq.booking.domain.dto.BookingExtensionDecisionRequest;
import rw.rra.roomiq.booking.domain.dto.BookingExtensionResponse;
import rw.rra.roomiq.booking.domain.dto.CancellationRequest;
import rw.rra.roomiq.booking.domain.dto.CreateBookingRequest;
import rw.rra.roomiq.booking.domain.dto.CreateBookingExtensionRequest;
import rw.rra.roomiq.booking.domain.dto.RoomEventRecipientsRequest;
import rw.rra.roomiq.booking.domain.entity.BookingExtension;
import rw.rra.roomiq.booking.domain.entity.Reservation;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;
import rw.rra.roomiq.booking.domain.entity.Meeting;
import rw.rra.roomiq.booking.domain.entity.Cancellation;
import rw.rra.roomiq.booking.domain.entity.ReservationOccurrence;
import rw.rra.roomiq.booking.domain.enums.ApprovalDecisionType;
import rw.rra.roomiq.booking.domain.enums.BookingExtensionStatus;
import rw.rra.roomiq.booking.domain.enums.BookingRequestStatus;
import rw.rra.roomiq.booking.domain.enums.BookingRequestType;
import rw.rra.roomiq.booking.domain.enums.CancellationReason;
import rw.rra.roomiq.booking.domain.enums.InviteStatus;
import rw.rra.roomiq.booking.domain.enums.MeetingVisibility;
import rw.rra.roomiq.booking.domain.enums.ParticipantRole;
import rw.rra.roomiq.booking.domain.enums.ReservationStatus;
import rw.rra.roomiq.booking.domain.repository.ApprovalDecisionRepository;
import rw.rra.roomiq.booking.domain.repository.BookingExtensionRepository;
import rw.rra.roomiq.booking.domain.repository.BookingEventOutboxRepository;
import rw.rra.roomiq.booking.domain.repository.BookingRequestRepository;
import rw.rra.roomiq.booking.domain.repository.CancellationRepository;
import rw.rra.roomiq.booking.domain.repository.MeetingParticipantRepository;
import rw.rra.roomiq.booking.domain.repository.MeetingRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationOccurrenceRepository;
import rw.rra.roomiq.booking.domain.service.BookingDecisionService;
import rw.rra.roomiq.booking.domain.service.BookingExtensionService;
import rw.rra.roomiq.booking.domain.service.BookingCancellationService;
import rw.rra.roomiq.booking.domain.service.BookingRequestService;
import rw.rra.roomiq.booking.domain.service.BookingRoomEventRecipientsService;
import rw.rra.roomiq.booking.domain.service.ReservationLifecycleService;
import rw.rra.roomiq.booking.integration.BookingAuthorizationClient;
import rw.rra.roomiq.booking.integration.BookingAuthorizationRequest;
import rw.rra.roomiq.booking.integration.BookingAuthorizationResponse;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient.BookingRequestFacts;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient.SchedulingOccurrence;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient.ValidatedBookingReferences;
import rw.rra.roomiq.common.web.DomainException;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@Import(BookingDecisionPostgresTests.TestClients.class)
class BookingDecisionPostgresTests {
    private static final String TOKEN = "Bearer approval-test-token";
    private static final Instant START = Instant.parse("2030-04-10T10:00:00Z");
    private static final Instant END = Instant.parse("2030-04-10T11:00:00Z");
    private static final String FAILING_PARTICIPANT_TRIGGER = "trg_booking_test_fail_participant";
    private static final String FAILING_PARTICIPANT_FUNCTION = "booking_test_fail_participant";
    private static final String FAILING_CANCELLATION_TRIGGER = "trg_booking_test_fail_cancellation";
    private static final String FAILING_CANCELLATION_FUNCTION = "booking_test_fail_cancellation";
    private static final String FAILING_EXTENSION_TRIGGER = "trg_booking_test_fail_extension_occurrence";
    private static final String FAILING_EXTENSION_FUNCTION = "booking_test_fail_extension_occurrence";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("roomiq.booking.event-relay.enabled", () -> "false");
    }

    @Autowired
    private BookingDecisionService decisionService;
    @Autowired
    private ReservationLifecycleService reservationLifecycleService;
    @Autowired
    private BookingCancellationService cancellationService;
    @Autowired
    private BookingExtensionService extensionService;
    @Autowired
    private BookingRequestService bookingRequestService;
    @Autowired
    private BookingRoomEventRecipientsService roomEventRecipientsService;
    @Autowired
    private BookingRequestRepository bookingRequests;
    @Autowired
    private ApprovalDecisionRepository decisions;
    @Autowired
    private CancellationRepository cancellations;
    @Autowired
    private ReservationRepository reservations;
    @Autowired
    private ReservationOccurrenceRepository reservationOccurrences;
    @Autowired
    private BookingExtensionRepository extensions;
    @Autowired
    private BookingEventOutboxRepository outbox;
    @Autowired
    private MeetingRepository meetings;
    @Autowired
    private MeetingParticipantRepository participants;
    @Autowired
    private BookingAuthorizationClient authorizationClient;
    @Autowired
    private BookingOwnerServicesClient ownerServicesClient;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        reset(authorizationClient, ownerServicesClient);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", TOKEN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequestContextAndFailureTrigger() {
        RequestContextHolder.resetRequestAttributes();
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + FAILING_PARTICIPANT_TRIGGER + " ON meeting_participant");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + FAILING_PARTICIPANT_FUNCTION + "()");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + FAILING_CANCELLATION_TRIGGER + " ON cancellation");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + FAILING_CANCELLATION_FUNCTION + "()");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + FAILING_EXTENSION_TRIGGER + " ON reservation_occurrence");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + FAILING_EXTENSION_FUNCTION + "()");
    }

    @Test
    void submittedSecretaryRequestPublishesVersionTwoScopedAdminRoutingFacts() {
        UUID requesterId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();
        BookingRequest draft = bookingRequests.saveAndFlush(new BookingRequest(
                "BR-" + UUID.randomUUID(), BookingRequestType.SECRETARY_REQUEST, requesterId,
                departmentId, roomId, buildingId, null, "Routing test", "Purpose", START, END, 3,
                false, BookingRequestStatus.DRAFT, "request-routing-test", Instant.now()));
        when(authorizationClient.authorizeCurrentCaller(any(BookingAuthorizationRequest.class)))
                .thenReturn(requesterId);
        configureOwnerValidation(draft, 15);

        bookingRequestService.submit(draft.getId());

        assertThat(jdbcTemplate.queryForObject("""
                SELECT event_version FROM booking_event_outbox
                WHERE event_type = 'BookingRequested' AND aggregate_id = ?
                """, Integer.class, draft.getId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload -> 'payload' ->> 'notificationRoute'
                FROM booking_event_outbox
                WHERE event_type = 'BookingRequested' AND aggregate_id = ?
                """, String.class, draft.getId())).isEqualTo("SCOPED_ADMIN_REQUEST");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload -> 'payload' ->> 'requesterUserId'
                FROM booking_event_outbox
                WHERE event_type = 'BookingRequested' AND aggregate_id = ?
                """, String.class, draft.getId())).isEqualTo(requesterId.toString());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT jsonb_array_length(payload -> 'payload' -> 'recipientUserIds')
                FROM booking_event_outbox
                WHERE event_type = 'BookingRequested' AND aggregate_id = ?
                """, Integer.class, draft.getId())).isZero();
    }

    @Test
    void approvalCommitsDecisionRequestReservationMeetingAndRequesterOrganizerTogether() {
        BookingRequest request = pendingRequest(UUID.randomUUID(), UUID.randomUUID(), START, END);
        configureAuthorization(request, UUID.randomUUID());
        configureOwnerValidation(request, 15);

        var response = decisionService.decide(request.getId(),
                new BookingDecisionRequest(ApprovalDecisionType.APPROVED, "Approved by admin"));

        assertThat(bookingRequests.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingRequestStatus.APPROVED);
        var decision = decisions.findAll().stream()
                .filter(item -> item.getBookingRequest().getId().equals(request.getId())).findFirst().orElseThrow();
        assertThat(decision.getDecision()).isEqualTo(ApprovalDecisionType.APPROVED);
        assertThat(decision.getDecidedByUserId()).isNotEqualTo(request.getRequestedByUserId());
        assertThat(decision.getDecidedAt()).isNotNull();
        assertThat(decision.getComment()).isEqualTo("Approved by admin");

        Reservation reservation = reservations.findAll().stream()
                .filter(item -> item.getBookingRequest().getId().equals(request.getId())).findFirst().orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getOrganizerUserId()).isEqualTo(request.getRequestedByUserId());
        assertThat(reservation.getReleaseBufferMinutes()).isEqualTo(15);
        long occupiedUntilEpoch = jdbcTemplate.queryForObject("""
                SELECT EXTRACT(EPOCH FROM upper(occupied_period))::bigint
                FROM reservation WHERE id = ?
                """, Long.class, reservation.getId());
        assertThat(Instant.ofEpochSecond(occupiedUntilEpoch)).isEqualTo(END.plusSeconds(900));
        List<ReservationOccurrence> persistedOccurrences =
                reservationOccurrences.findAllByReservation_IdOrderByStartAt(reservation.getId());
        assertThat(persistedOccurrences).hasSize(1);
        assertThat(persistedOccurrences.getFirst().getStartAt()).isEqualTo(START);
        assertThat(persistedOccurrences.getFirst().getEndAt()).isEqualTo(END);
        Long occurrenceEnd = jdbcTemplate.queryForObject("""
                SELECT EXTRACT(EPOCH FROM upper(occupied_period))::bigint
                FROM reservation_occurrence WHERE id = ?
                """, Long.class, persistedOccurrences.getFirst().getId());
        assertThat(Instant.ofEpochSecond(occurrenceEnd)).isEqualTo(END.plusSeconds(900));

        var meeting = meetings.findAll().stream()
                .filter(item -> item.getReservation().getId().equals(reservation.getId())).findFirst().orElseThrow();
        assertThat(meeting.getTitle()).isEqualTo(request.getTitle());
        assertThat(meeting.getAgenda()).isEqualTo(request.getPurpose());
        assertThat(meeting.getOrganizerDisplayName()).isEqualTo("Trusted requester");
        assertThat(meeting.getVisibility()).isEqualTo(MeetingVisibility.PRIVATE);
        var organizer = participants.findAll().stream()
                .filter(item -> item.getMeeting().getId().equals(meeting.getId())).findFirst().orElseThrow();
        assertThat(organizer.getUserId()).isEqualTo(request.getRequestedByUserId());
        assertThat(organizer.getDisplayName()).isEqualTo("Trusted requester");
        assertThat(organizer.getRole()).isEqualTo(ParticipantRole.ORGANIZER);
        assertThat(organizer.getInviteStatus()).isEqualTo(InviteStatus.ACCEPTED);
        assertThat(response.reservationId()).isEqualTo(reservation.getId());
        assertThat(response.meetingId()).isEqualTo(meeting.getId());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM booking_event_outbox
                WHERE event_type = 'BookingApproved' AND aggregate_id = ?
                """, Integer.class, request.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload -> 'payload' ->> 'requesterUserId'
                FROM booking_event_outbox
                WHERE event_type = 'BookingApproved' AND aggregate_id = ?
                """, String.class, request.getId())).isEqualTo(request.getRequestedByUserId().toString());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload -> 'payload' ->> 'decisionComment'
                FROM booking_event_outbox
                WHERE event_type = 'BookingApproved' AND aggregate_id = ?
                """, String.class, request.getId())).isEqualTo("Approved by admin");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM booking_event_outbox
                WHERE event_type = 'Reminder' AND aggregate_id = ?
                """, Integer.class, meeting.getId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                SELECT (payload -> 'payload' ->> 'reminderOffsetMinutes')::integer
                FROM booking_event_outbox
                WHERE event_type = 'Reminder' AND aggregate_id = ?
                ORDER BY (payload -> 'payload' ->> 'reminderOffsetMinutes')::integer DESC
                """, Integer.class, meeting.getId())).containsExactly(15, 5);
        assertThat(jdbcTemplate.queryForList("""
                SELECT payload -> 'payload' ->> 'reminderAt'
                FROM booking_event_outbox
                WHERE event_type = 'Reminder' AND aggregate_id = ?
                ORDER BY (payload -> 'payload' ->> 'reminderOffsetMinutes')::integer DESC
                """, String.class, meeting.getId()))
                .containsExactly("2030-04-10T09:45:00Z", "2030-04-10T09:55:00Z");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM booking_event_outbox
                WHERE event_type = 'Reminder' AND aggregate_id = ?
                  AND payload -> 'payload' ->> 'timezone' = 'Africa/Kigali'
                """, Integer.class, meeting.getId())).isEqualTo(2);
        assertThat(outbox.findAll()).filteredOn(entry -> entry.getAggregateId().equals(reservation.getId()))
                .hasSize(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload ->> 'action'
                FROM booking_event_outbox
                WHERE aggregate_id = ?
                """, String.class, reservation.getId())).isEqualTo("UPSERT");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload ->> 'occupiedInterval'
                FROM booking_event_outbox
                WHERE aggregate_id = ?
                """, String.class, reservation.getId())).contains("2030-04-10T11:15:00Z");
    }

    @Test
    void roomEventRecipientLookupReturnsOnlyOverlappingActiveInternalUsersInAuthorizedBuilding()
            throws SQLException {
        UUID roomId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();
        UUID confirmedOrganizerId = UUID.randomUUID();
        UUID confirmedParticipantId = UUID.randomUUID();
        Reservation confirmed = confirmedReservation(confirmedOrganizerId, roomId, START, END);
        setRequestBuilding(confirmed, buildingId);
        Meeting confirmedMeeting = meetings.findByReservation_Id(confirmed.getId()).orElseThrow();
        participants.saveAndFlush(new rw.rra.roomiq.booking.domain.entity.MeetingParticipant(
                confirmedMeeting, confirmedParticipantId, null, null, ParticipantRole.REQUIRED, InviteStatus.INVITED));
        participants.saveAndFlush(new rw.rra.roomiq.booking.domain.entity.MeetingParticipant(
                confirmedMeeting, null, "external@example.invalid", "External", ParticipantRole.REQUIRED,
                InviteStatus.INVITED));

        UUID inProgressOrganizerId = UUID.randomUUID();
        Reservation inProgress = confirmedReservation(inProgressOrganizerId, roomId,
                START.plusSeconds(7200), END.plusSeconds(7200));
        setRequestBuilding(inProgress, buildingId);
        assertThat(inProgress.checkIn(START.plusSeconds(7200))).isTrue();
        reservations.saveAndFlush(inProgress);

        UUID cancelledOrganizerId = UUID.randomUUID();
        Reservation cancelled = confirmedReservation(cancelledOrganizerId, roomId,
                START.plusSeconds(14_400), END.plusSeconds(14_400));
        setRequestBuilding(cancelled, buildingId);
        assertThat(cancelled.cancel()).isTrue();
        reservations.saveAndFlush(cancelled);
        reservationOccurrences.findAllByReservation_IdOrderByStartAt(cancelled.getId())
                .forEach(ReservationOccurrence::releaseOccupancy);
        reservationOccurrences.flush();

        UUID outsideWindowOrganizerId = UUID.randomUUID();
        Reservation outsideWindow = confirmedReservation(outsideWindowOrganizerId, roomId,
                START.plusSeconds(172_800), END.plusSeconds(172_800));
        setRequestBuilding(outsideWindow, buildingId);

        when(authorizationClient.authorizeRoomManagement(buildingId)).thenReturn(UUID.randomUUID());

        var response = roomEventRecipientsService.resolve(new RoomEventRecipientsRequest(
                roomId, buildingId, START, END.plusSeconds(18_000), "v1"));

        assertThat(response.contractVersion()).isEqualTo("v1");
        assertThat(response.roomId()).isEqualTo(roomId);
        assertThat(response.recipientUserIds()).containsExactlyInAnyOrder(
                confirmedOrganizerId, confirmedParticipantId, inProgressOrganizerId);
        assertThat(response.recipientUserIds()).doesNotContain(
                cancelledOrganizerId, outsideWindowOrganizerId);

        var statusRecipients = roomEventRecipientsService.resolve(new RoomEventRecipientsRequest(
                roomId, buildingId, START, null, "v1"));
        assertThat(statusRecipients.recipientUserIds()).contains(
                confirmedOrganizerId, confirmedParticipantId, inProgressOrganizerId, outsideWindowOrganizerId);
        assertThat(statusRecipients.recipientUserIds()).doesNotContain(cancelledOrganizerId);

        var adjacentWindow = roomEventRecipientsService.resolve(new RoomEventRecipientsRequest(
                roomId, buildingId, END.plusSeconds(300), END.plusSeconds(900), "v1"));
        assertThat(adjacentWindow.recipientUserIds()).isEmpty();
    }

    @Test
    void checkInAndCompletionSetIndependentServerTimestampsAtomically() throws SQLException {
        UUID organizerId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Reservation reservation = confirmedReservation(organizerId, UUID.randomUUID(), START, END);
        Meeting meeting = meetings.findByReservation_Id(reservation.getId()).orElseThrow();
        participants.saveAndFlush(new rw.rra.roomiq.booking.domain.entity.MeetingParticipant(
                meeting, organizerId, null, null, ParticipantRole.ORGANIZER, InviteStatus.ACCEPTED));
        participants.saveAndFlush(new rw.rra.roomiq.booking.domain.entity.MeetingParticipant(
                meeting, participantId, null, null, ParticipantRole.REQUIRED, InviteStatus.ACCEPTED));
        UUID actor = UUID.randomUUID();
        when(authorizationClient.authorizeReservationLifecycle(
                reservation.getOrganizerUserId(), reservation.getBookingRequest().getDepartmentId(),
                reservation.getBookingRequest().getOfficeBuildingId())).thenReturn(actor);

        var checkedIn = reservationLifecycleService.checkIn(reservation.getId());

        assertThat(checkedIn.status()).isEqualTo(ReservationStatus.IN_PROGRESS);
        assertThat(checkedIn.checkedInAt()).isNotNull();
        assertThat(checkedIn.completedAt()).isNull();
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getCheckedInAt())
                .isEqualTo(checkedIn.checkedInAt());
        verify(authorizationClient).authorizeReservationLifecycle(reservation.getOrganizerUserId(),
                reservation.getBookingRequest().getDepartmentId(),
                reservation.getBookingRequest().getOfficeBuildingId());

        var completed = reservationLifecycleService.complete(reservation.getId());

        assertThat(completed.status()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(completed.checkedInAt()).isEqualTo(checkedIn.checkedInAt());
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.completedAt().compareTo(checkedIn.checkedInAt())).isGreaterThanOrEqualTo(0);
        Reservation persisted = reservations.findById(reservation.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(persisted.getCheckedInAt()).isEqualTo(checkedIn.checkedInAt());
        assertThat(persisted.getCompletedAt()).isEqualTo(completed.completedAt());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT (status = 'COMPLETED') = (completed_at IS NOT NULL)
                FROM reservation WHERE id = ?
                """, Boolean.class, reservation.getId())).isTrue();
        String completedPayload = jdbcTemplate.queryForObject("""
                SELECT payload::text FROM booking_event_outbox
                WHERE event_type = 'MeetingCompleted'
                  AND payload -> 'payload' ->> 'reservationId' = ?
                """, String.class, reservation.getId().toString());
        assertThat(completedPayload).contains(organizerId.toString()).contains(participantId.toString());
    }

    @Test
    void explicitReleaseAtomicallyReleasesEveryOccurrenceAndNotifiesInternalParticipants()
            throws SQLException {
        UUID organizerId = UUID.randomUUID();
        UUID internalParticipantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Reservation reservation = confirmedReservation(organizerId, UUID.randomUUID(), START, END);
        Meeting meeting = meetings.findByReservation_Id(reservation.getId()).orElseThrow();
        participants.saveAndFlush(new rw.rra.roomiq.booking.domain.entity.MeetingParticipant(
                meeting, organizerId, null, null, ParticipantRole.ORGANIZER, InviteStatus.ACCEPTED));
        participants.saveAndFlush(new rw.rra.roomiq.booking.domain.entity.MeetingParticipant(
                meeting, internalParticipantId, null, null, ParticipantRole.REQUIRED, InviteStatus.ACCEPTED));
        participants.saveAndFlush(new rw.rra.roomiq.booking.domain.entity.MeetingParticipant(
                meeting, null, "external@example.invalid", "External", ParticipantRole.REQUIRED,
                InviteStatus.INVITED));
        when(authorizationClient.authorizeReservationLifecycle(organizerId,
                reservation.getBookingRequest().getDepartmentId(),
                reservation.getBookingRequest().getOfficeBuildingId())).thenReturn(actorId);

        var released = reservationLifecycleService.release(reservation.getId());

        assertThat(released.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservationOccurrences.findAllByReservation_IdOrderByStartAt(reservation.getId()))
                .allSatisfy(occurrence -> assertThat(occurrence.isOccupancyActive()).isFalse());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM booking_event_outbox
                WHERE event_type = 'booking.occupancy.changed'
                  AND payload ->> 'action' = 'REMOVE'
                  AND payload ->> 'reservationId' = ?
                """, Integer.class, reservation.getId().toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM booking_event_outbox
                WHERE event_type = 'MeetingReleased'
                  AND payload -> 'payload' ->> 'meetingId' = ?
                  AND payload -> 'payload' ->> 'releasedByUserId' = ?
                  AND payload -> 'payload' ->> 'releasedAt' IS NOT NULL
                """, Integer.class, meeting.getId().toString(), actorId.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload -> 'payload' -> 'recipientUserIds'
                FROM booking_event_outbox WHERE event_type = 'MeetingReleased'
                  AND payload -> 'payload' ->> 'meetingId' = ?
                """, String.class, meeting.getId().toString()))
                .contains(organizerId.toString(), internalParticipantId.toString())
                .doesNotContain("external@example.invalid");
    }

    @Test
    void explicitReleaseRejectsCompletedReservationsWithoutCreatingReleaseEvents() throws SQLException {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        when(authorizationClient.authorizeReservationLifecycle(any(UUID.class), any(UUID.class), any(UUID.class)))
                .thenReturn(UUID.randomUUID());
        reservationLifecycleService.checkIn(reservation.getId());
        reservationLifecycleService.complete(reservation.getId());

        assertLifecycleConflict(() -> reservationLifecycleService.release(reservation.getId()));

        assertThat(reservations.findById(reservation.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.COMPLETED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM booking_event_outbox WHERE event_type = 'MeetingReleased'",
                Integer.class)).isZero();
    }

    @Test
    void explicitReleaseIsSupportedForAnInProgressReservation() throws SQLException {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        when(authorizationClient.authorizeReservationLifecycle(any(UUID.class), any(UUID.class), any(UUID.class)))
                .thenReturn(UUID.randomUUID());
        reservationLifecycleService.checkIn(reservation.getId());

        var released = reservationLifecycleService.release(reservation.getId());

        assertThat(released.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservationOccurrences.findAllByReservation_IdOrderByStartAt(reservation.getId()))
                .allSatisfy(occurrence -> assertThat(occurrence.isOccupancyActive()).isFalse());
    }

    @Test
    void explicitReleaseOutboxFailureRollsBackReservationAndOccupancyChanges() throws SQLException {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        when(authorizationClient.authorizeReservationLifecycle(any(UUID.class), any(UUID.class), any(UUID.class)))
                .thenReturn(UUID.randomUUID());
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION reject_meeting_release_outbox_insert()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'simulated MeetingReleased outbox failure';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_booking_test_reject_meeting_release
                BEFORE INSERT ON booking_event_outbox
                FOR EACH ROW WHEN (NEW.event_type = 'MeetingReleased')
                EXECUTE FUNCTION reject_meeting_release_outbox_insert()
                """);
        try {
            assertThatThrownBy(() -> reservationLifecycleService.release(reservation.getId()))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_booking_test_reject_meeting_release "
                    + "ON booking_event_outbox");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_meeting_release_outbox_insert()");
        }

        assertThat(reservations.findById(reservation.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservationOccurrences.findAllByReservation_IdOrderByStartAt(reservation.getId()))
                .allSatisfy(occurrence -> assertThat(occurrence.isOccupancyActive()).isTrue());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM booking_event_outbox
                WHERE event_type = 'booking.occupancy.changed'
                  AND payload ->> 'action' = 'REMOVE'
                  AND payload ->> 'reservationId' = ?
                """, Integer.class, reservation.getId().toString())).isZero();
    }

    @Test
    void invalidAndDuplicateLifecycleTransitionsLeavePersistedTimestampsUnchanged() throws SQLException {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        when(authorizationClient.authorizeReservationLifecycle(any(UUID.class), any(UUID.class), any(UUID.class)))
                .thenReturn(UUID.randomUUID());

        assertLifecycleConflict(() -> reservationLifecycleService.complete(reservation.getId()));
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getCompletedAt()).isNull();

        var checkedIn = reservationLifecycleService.checkIn(reservation.getId());
        assertLifecycleConflict(() -> reservationLifecycleService.checkIn(reservation.getId()));
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getCheckedInAt())
                .isEqualTo(checkedIn.checkedInAt());

        var completed = reservationLifecycleService.complete(reservation.getId());
        assertLifecycleConflict(() -> reservationLifecycleService.complete(reservation.getId()));
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getCompletedAt())
                .isEqualTo(completed.completedAt());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE reservation SET status = 'COMPLETED', completed_at = NULL WHERE id = ?",
                reservation.getId())).isInstanceOf(RuntimeException.class);
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getCompletedAt())
                .isEqualTo(completed.completedAt());
    }

    @Test
    void identityAuthorizationFailureDoesNotChangeReservationLifecycle() throws SQLException {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        when(authorizationClient.authorizeReservationLifecycle(any(UUID.class), any(UUID.class), any(UUID.class)))
                .thenThrow(new DomainException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Denied"));

        assertThatThrownBy(() -> reservationLifecycleService.checkIn(reservation.getId()))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).status()).isEqualTo(HttpStatus.FORBIDDEN));
        when(authorizationClient.authorizeReservationLifecycle(any(UUID.class), any(UUID.class), any(UUID.class)))
                .thenReturn(UUID.randomUUID());
        var checkedIn = reservationLifecycleService.checkIn(reservation.getId());
        reset(authorizationClient);
        when(authorizationClient.authorizeReservationLifecycle(any(UUID.class), any(UUID.class), any(UUID.class)))
                .thenThrow(new DomainException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Denied"));
        assertThatThrownBy(() -> reservationLifecycleService.complete(reservation.getId()))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).status()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> reservationLifecycleService.release(reservation.getId()))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).status()).isEqualTo(HttpStatus.FORBIDDEN));
        Reservation unchanged = reservations.findById(reservation.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ReservationStatus.IN_PROGRESS);
        assertThat(unchanged.getCheckedInAt()).isEqualTo(checkedIn.checkedInAt());
        assertThat(unchanged.getCompletedAt()).isNull();
    }

    @Test
    void concurrentDuplicateCheckInsCommitOnlyOneLifecycleTransition() throws Exception {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        when(authorizationClient.authorizeReservationLifecycle(any(UUID.class), any(UUID.class), any(UUID.class)))
                .thenAnswer(invocation -> UUID.randomUUID());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> checkIn = () -> {
            MockHttpServletRequest httpRequest = new MockHttpServletRequest();
            httpRequest.addHeader("Authorization", TOKEN);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            try {
                reservationLifecycleService.checkIn(reservation.getId());
                return "checked-in";
            } catch (DomainException exception) {
                return exception.code();
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        };
        try {
            Future<String> first = executor.submit(checkIn);
            Future<String> second = executor.submit(checkIn);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("checked-in", "RESERVATION_STATE_CONFLICT");
        } finally {
            executor.shutdownNow();
        }
        Reservation persisted = reservations.findById(reservation.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReservationStatus.IN_PROGRESS);
        assertThat(persisted.getCheckedInAt()).isNotNull();
        assertThat(persisted.getCompletedAt()).isNull();
    }

    @Test
    void completedReservationStillOccupiesItsBufferedHalfOpenInterval() throws SQLException {
        UUID roomId = UUID.randomUUID();
        Reservation reservation = confirmedReservation(UUID.randomUUID(), roomId, START, END);
        when(authorizationClient.authorizeReservationLifecycle(any(UUID.class), any(UUID.class), any(UUID.class)))
                .thenReturn(UUID.randomUUID());
        reservationLifecycleService.checkIn(reservation.getId());
        reservationLifecycleService.complete(reservation.getId());

        BookingRequest overlapping = pendingRequest(UUID.randomUUID(), roomId, START.plusSeconds(30), END);
        configureAuthorization(overlapping, UUID.randomUUID());
        configureOwnerValidation(overlapping, 5);

        assertThatThrownBy(() -> decisionService.decide(overlapping.getId(),
                new BookingDecisionRequest(ApprovalDecisionType.APPROVED, null)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).code())
                        .isEqualTo("ROOM_OCCUPANCY_CONFLICT"));
        assertThat(reservationOccurrences.existsRoomOccupancyConflict(
                roomId, START, END.plusSeconds(300))).isTrue();
    }

    @Test
    void terminalReservationsCannotReenterTheLifecycle() throws SQLException {
        when(authorizationClient.authorizeReservationLifecycle(any(UUID.class), any(UUID.class), any(UUID.class)))
                .thenReturn(UUID.randomUUID());
        for (ReservationStatus terminalStatus : List.of(ReservationStatus.CANCELLED, ReservationStatus.RELEASED)) {
            Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
            jdbcTemplate.update("UPDATE reservation SET status = ? WHERE id = ?",
                    terminalStatus.name(), reservation.getId());

            assertLifecycleConflict(() -> reservationLifecycleService.checkIn(reservation.getId()));
            assertLifecycleConflict(() -> reservationLifecycleService.complete(reservation.getId()));
            Reservation unchanged = reservations.findById(reservation.getId()).orElseThrow();
            assertThat(unchanged.getStatus()).isEqualTo(terminalStatus);
            assertThat(unchanged.getCheckedInAt()).isNull();
            assertThat(unchanged.getCompletedAt()).isNull();
        }
    }

    @Test
    void extensionShapedOverlapsAreRejectedByBothDatabaseExclusionBarriers() throws SQLException {
        UUID roomId = UUID.randomUUID();
        Reservation first = confirmedReservation(UUID.randomUUID(), roomId, START, END);
        Instant secondStart = END.plusSeconds(3_600);
        Reservation second = confirmedReservation(UUID.randomUUID(), roomId, secondStart,
                secondStart.plusSeconds(3_600));
        Instant overlappingEnd = second.getEndAt().plusSeconds(300);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE reservation
                SET occupied_period = tstzrange(?::timestamptz, ?::timestamptz, '[)')
                WHERE id = ?
                """, Timestamp.from(first.getStartAt()), Timestamp.from(overlappingEnd), first.getId()))
                .satisfies(error -> assertExclusionViolation(error, "ex_reservation_room_occupied_period"));
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE reservation_occurrence
                SET occupied_period = tstzrange(?::timestamptz, ?::timestamptz, '[)')
                WHERE reservation_id = ?
                """, Timestamp.from(first.getStartAt()), Timestamp.from(overlappingEnd), first.getId()))
                .satisfies(error -> assertExclusionViolation(
                        error, "ex_reservation_occurrence_room_occupied_period"));
        Long reservationEnd = jdbcTemplate.queryForObject("""
                SELECT EXTRACT(EPOCH FROM upper(occupied_period))::bigint
                FROM reservation WHERE id = ?
                """, Long.class, first.getId());
        Long occurrenceEnd = jdbcTemplate.queryForObject("""
                SELECT EXTRACT(EPOCH FROM upper(occupied_period))::bigint
                FROM reservation_occurrence WHERE reservation_id = ?
                """, Long.class, first.getId());
        assertThat(Instant.ofEpochSecond(reservationEnd)).isEqualTo(END.plusSeconds(300));
        assertThat(Instant.ofEpochSecond(occurrenceEnd)).isEqualTo(END.plusSeconds(300));
        assertThat(second.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void extensionApprovalUpdatesBothOccupancyBarriersAndPreservesDecisionHistory() throws SQLException {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        UUID administratorId = UUID.randomUUID();
        configureExtensionAuthorization(reservation, administratorId);
        configureOwnerValidation(reservation.getBookingRequest(), 15);

        var pending = extensionService.request(reservation.getId(),
                new CreateBookingExtensionRequest(END.plusSeconds(3_600)), "extension-approval");
        assertThat(pending.status()).isEqualTo(BookingExtensionStatus.PENDING);
        assertThat(pending.previousEndAt()).isEqualTo(END);
        assertThat(pending.requestedByUserId()).isEqualTo(reservation.getOrganizerUserId());

        var approved = extensionService.decide(pending.extensionId(),
                new BookingExtensionDecisionRequest(ApprovalDecisionType.APPROVED, "Approved by admin"));

        assertThat(approved.status()).isEqualTo(BookingExtensionStatus.APPROVED);
        assertThat(approved.decidedByUserId()).isEqualTo(administratorId);
        assertThat(approved.decidedByUserId()).isNotEqualTo(approved.requestedByUserId());
        assertThat(approved.approvedEndAt()).isEqualTo(END.plusSeconds(3_600));
        assertThat(approved.decisionComment()).isEqualTo("Approved by admin");
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getEndAt())
                .isEqualTo(END.plusSeconds(3_600));
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getReleaseBufferMinutes()).isEqualTo(15);
        assertThat(reservationOccurrences.findAllByReservation_IdOrderByStartAt(reservation.getId()))
                .singleElement().satisfies(occurrence ->
                        assertThat(occurrence.getEndAt()).isEqualTo(END.plusSeconds(3_600)));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM booking_event_outbox
                WHERE aggregate_id = ? AND payload ->> 'action' = 'UPSERT'
                """, Integer.class, reservation.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload -> 'occupiedInterval' ->> 'endsAt'
                FROM booking_event_outbox
                WHERE aggregate_id = ? AND payload ->> 'action' = 'UPSERT'
                """, String.class, reservation.getId())).isEqualTo("2030-04-10T12:15:00Z");
        Long reservationEnd = jdbcTemplate.queryForObject("""
                SELECT EXTRACT(EPOCH FROM upper(occupied_period))::bigint
                FROM reservation WHERE id = ?
                """, Long.class, reservation.getId());
        Long occurrenceEnd = jdbcTemplate.queryForObject("""
                SELECT EXTRACT(EPOCH FROM upper(occupied_period))::bigint
                FROM reservation_occurrence WHERE reservation_id = ?
                """, Long.class, reservation.getId());
        assertThat(Instant.ofEpochSecond(reservationEnd)).isEqualTo(END.plusSeconds(4_500));
        assertThat(Instant.ofEpochSecond(occurrenceEnd)).isEqualTo(END.plusSeconds(4_500));
        assertThat(extensions.findAll()).filteredOn(extension -> extension.getReservation().getId()
                .equals(reservation.getId())).hasSize(1);
    }

    @Test
    void recurringExtensionIsRejectedAndNoPendingHistoryIsCreated() throws SQLException {
        UUID organizerId = UUID.randomUUID();
        UUID recurrenceRuleId = UUID.randomUUID();
        BookingRequest request = pendingRecurringRequest(organizerId, UUID.randomUUID(), recurrenceRuleId,
                START, END);
        request.approve();
        bookingRequests.saveAndFlush(request);
        Reservation reservation = reservations.saveAndFlush(new Reservation(request, request.getRoomId(),
                organizerId, recurrenceRuleId, occupiedPeriod(START, END.plusSeconds(300)),
                START, END, 5, ReservationStatus.CONFIRMED, Instant.now()));
        reservationOccurrences.saveAndFlush(new ReservationOccurrence(reservation, request.getRoomId(),
                START, END, occupiedPeriod(START, END.plusSeconds(300))));
        when(authorizationClient.authorizeCurrentCaller(any(BookingAuthorizationRequest.class)))
                .thenReturn(organizerId);

        assertThatThrownBy(() -> extensionService.request(reservation.getId(),
                new CreateBookingExtensionRequest(END.plusSeconds(3_600)), null))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException exception = (DomainException) error;
                    assertThat(exception.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(exception.code()).isEqualTo("RECURRING_RESERVATION_EXTENSION_UNSUPPORTED");
                    assertThat(exception.getMessage()).contains("Recurring reservations");
                });
        assertThat(extensions.findAll()).noneMatch(extension ->
                extension.getReservation().getId().equals(reservation.getId()));
    }

    @Test
    void extensionSelfDecisionIsDeniedAndPendingStateIsPreserved() throws SQLException {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        UUID requesterId = reservation.getOrganizerUserId();
        when(authorizationClient.authorizeCurrentCaller(any(BookingAuthorizationRequest.class)))
                .thenReturn(requesterId);
        BookingExtension extension = extensions.saveAndFlush(new rw.rra.roomiq.booking.domain.entity.BookingExtension(
                reservation, requesterId, END, END.plusSeconds(3_600), null, BookingExtensionStatus.PENDING,
                null, null, null, Instant.now(), null));

        assertThatThrownBy(() -> extensionService.decide(extension.getId(),
                new BookingExtensionDecisionRequest(ApprovalDecisionType.APPROVED, null)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).code())
                        .isEqualTo("BOOKING_EXTENSION_SELF_DECISION_DENIED"));
        assertThat(extensions.findById(extension.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingExtensionStatus.PENDING);
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getEndAt()).isEqualTo(END);
    }

    @Test
    void extensionRequestAuthorizationIsCheckedAgainstThePersistedOrganizer() throws SQLException {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        when(authorizationClient.authorizeCurrentCaller(any(BookingAuthorizationRequest.class)))
                .thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> extensionService.request(reservation.getId(),
                new CreateBookingExtensionRequest(END.plusSeconds(3_600)), null))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).code())
                        .isEqualTo("BOOKING_EXTENSION_REQUEST_DENIED"));
        assertThat(extensions.findAll()).noneMatch(extension ->
                extension.getReservation().getId().equals(reservation.getId()));
    }

    @Test
    void extensionConflictingWithNextReservationIsRejectedWithoutChangingOccupancy() throws SQLException {
        UUID roomId = UUID.randomUUID();
        Reservation reservation = confirmedReservation(UUID.randomUUID(), roomId, START, END);
        Reservation next = confirmedReservation(UUID.randomUUID(), roomId, END.plusSeconds(1_800),
                END.plusSeconds(5_400));
        UUID administratorId = UUID.randomUUID();
        configureExtensionAuthorization(reservation, administratorId);
        configureOwnerValidation(reservation.getBookingRequest(), 5);
        var pending = extensionService.request(reservation.getId(),
                new CreateBookingExtensionRequest(END.plusSeconds(3_600)), "extension-overlap");

        assertThatThrownBy(() -> extensionService.decide(pending.extensionId(),
                new BookingExtensionDecisionRequest(ApprovalDecisionType.APPROVED, null)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).code())
                        .isEqualTo("ROOM_OCCUPANCY_CONFLICT"));

        assertThat(reservations.findById(reservation.getId()).orElseThrow().getEndAt()).isEqualTo(END);
        assertThat(extensions.findById(pending.extensionId()).orElseThrow().getStatus())
                .isEqualTo(BookingExtensionStatus.PENDING);
        assertThat(reservations.findById(next.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void extensionRequestIdempotencyReplaysOnlyMatchingReservationAndEnd() throws SQLException {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        UUID requesterId = reservation.getOrganizerUserId();
        when(authorizationClient.authorizeCurrentCaller(any(BookingAuthorizationRequest.class)))
                .thenReturn(requesterId);
        configureOwnerValidation(reservation.getBookingRequest(), 5);
        CreateBookingExtensionRequest request = new CreateBookingExtensionRequest(END.plusSeconds(3_600));

        var created = extensionService.request(reservation.getId(), request, "extension-idempotency");
        var replay = extensionService.request(reservation.getId(), request, "extension-idempotency");

        assertThat(replay.extensionId()).isEqualTo(created.extensionId());
        assertThat(extensions.findAll()).filteredOn(extension -> extension.getReservation().getId()
                .equals(reservation.getId())).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT event_version FROM booking_event_outbox
                WHERE event_type = 'ExtensionRequested' AND aggregate_id = ?
                """, Integer.class, created.extensionId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload -> 'payload' ->> 'notificationRoute'
                FROM booking_event_outbox
                WHERE event_type = 'ExtensionRequested' AND aggregate_id = ?
                """, String.class, created.extensionId())).isEqualTo("SCOPED_ADMIN_REQUEST");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT jsonb_array_length(payload -> 'payload' -> 'recipientUserIds')
                FROM booking_event_outbox
                WHERE event_type = 'ExtensionRequested' AND aggregate_id = ?
                """, Integer.class, created.extensionId())).isZero();
        assertThatThrownBy(() -> extensionService.request(reservation.getId(),
                new CreateBookingExtensionRequest(END.plusSeconds(7_200)), "extension-idempotency"))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).code())
                        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void concurrentExtensionRetriesWithSameKeyCreateOnlyOneExtension() throws Exception {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        configureExtensionAuthorization(reservation, UUID.randomUUID());
        configureOwnerValidation(reservation.getBookingRequest(), 5);
        CreateBookingExtensionRequest request = new CreateBookingExtensionRequest(END.plusSeconds(3_600));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BookingExtensionResponse> first =
                    executor.submit(() -> requestExtension(reservation.getId(), request, ready, start));
            Future<BookingExtensionResponse> second =
                    executor.submit(() -> requestExtension(reservation.getId(), request, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            BookingExtensionResponse firstResult = first.get(30, TimeUnit.SECONDS);
            BookingExtensionResponse secondResult = second.get(30, TimeUnit.SECONDS);
            assertThat(firstResult.extensionId()).isEqualTo(secondResult.extensionId());
        } finally {
            executor.shutdownNow();
        }
        assertThat(extensions.findAll()).filteredOn(extension -> extension.getReservation().getId()
                .equals(reservation.getId())).hasSize(1);
    }

    @Test
    void concurrentBookingRequestRetriesWithSameKeyCreateOnlyOneRequest() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        when(authorizationClient.authorizeCurrentCaller(any(BookingAuthorizationRequest.class)))
                .thenReturn(actorId);
        configureOwnerValidation(new BookingRequest("BR-validation", BookingRequestType.SECRETARY_REQUEST,
                actorId, departmentId, roomId, buildingId, null, "Retry test", null, START, END, 2,
                false, BookingRequestStatus.DRAFT, null, Instant.now()), 5);
        CreateBookingRequest request = new CreateBookingRequest(departmentId, roomId, buildingId,
                null, "Retry test", null, START, END, 2, false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first =
                    executor.submit(() -> createBookingRequest(request, ready, start));
            Future<String> second =
                    executor.submit(() -> createBookingRequest(request, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            String firstReference = first.get(30, TimeUnit.SECONDS);
            String secondReference = second.get(30, TimeUnit.SECONDS);
            assertThat(firstReference).isEqualTo(secondReference);
        } finally {
            executor.shutdownNow();
        }
        assertThat(bookingRequests.findAll())
                .filteredOn(value -> "concurrent-request-key".equals(value.getIdempotencyKey())).hasSize(1);
    }

    @Test
    void extensionRejectionRecordsScopedDecisionWithoutChangingReservation() throws SQLException {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        UUID administratorId = UUID.randomUUID();
        configureExtensionAuthorization(reservation, administratorId);
        configureOwnerValidation(reservation.getBookingRequest(), 5);
        var pending = extensionService.request(reservation.getId(),
                new CreateBookingExtensionRequest(END.plusSeconds(3_600)), null);

        var rejected = extensionService.decide(pending.extensionId(),
                new BookingExtensionDecisionRequest(ApprovalDecisionType.REJECTED, "Room is booked next"));

        assertThat(rejected.status()).isEqualTo(BookingExtensionStatus.REJECTED);
        assertThat(rejected.decidedByUserId()).isEqualTo(administratorId);
        assertThat(rejected.decisionComment()).isEqualTo("Room is booked next");
        assertThat(rejected.approvedEndAt()).isNull();
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getEndAt()).isEqualTo(END);
        assertThat(reservationOccurrences.findAllByReservation_IdOrderByStartAt(reservation.getId()))
                .singleElement().satisfies(occurrence -> assertThat(occurrence.getEndAt()).isEqualTo(END));
    }

    @Test
    void failedOccurrenceUpdateRollsBackExtensionDecisionAndReservationChange() throws SQLException {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        UUID administratorId = UUID.randomUUID();
        configureExtensionAuthorization(reservation, administratorId);
        configureOwnerValidation(reservation.getBookingRequest(), 5);
        var pending = extensionService.request(reservation.getId(),
                new CreateBookingExtensionRequest(END.plusSeconds(3_600)), "extension-rollback");
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION booking_test_fail_extension_occurrence() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION USING ERRCODE = '23514',
                        CONSTRAINT = 'test_extension_occurrence_failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_booking_test_fail_extension_occurrence
                BEFORE UPDATE ON reservation_occurrence
                FOR EACH ROW EXECUTE FUNCTION booking_test_fail_extension_occurrence()
                """);

        assertThatThrownBy(() -> extensionService.decide(pending.extensionId(),
                new BookingExtensionDecisionRequest(ApprovalDecisionType.APPROVED, null)))
                .isInstanceOf(RuntimeException.class);

        assertThat(reservations.findById(reservation.getId()).orElseThrow().getEndAt()).isEqualTo(END);
        assertThat(extensions.findById(pending.extensionId()).orElseThrow().getStatus())
                .isEqualTo(BookingExtensionStatus.PENDING);
        assertThat(reservationOccurrences.findAllByReservation_IdOrderByStartAt(reservation.getId()))
                .singleElement().satisfies(occurrence -> assertThat(occurrence.getEndAt()).isEqualTo(END));
    }

    @Test
    void concurrentExtensionDecisionsPersistOnlyOneOutcome() throws Exception {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        UUID administratorId = UUID.randomUUID();
        configureExtensionAuthorization(reservation, administratorId);
        configureOwnerValidation(reservation.getBookingRequest(), 5);
        var pending = extensionService.request(reservation.getId(),
                new CreateBookingExtensionRequest(END.plusSeconds(3_600)), "extension-concurrent");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<String> approve = () -> decideExtension(extensionService, pending.extensionId(),
                new BookingExtensionDecisionRequest(ApprovalDecisionType.APPROVED, null), ready, start);
        Callable<String> reject = () -> decideExtension(extensionService, pending.extensionId(),
                new BookingExtensionDecisionRequest(ApprovalDecisionType.REJECTED, null), ready, start);
        try {
            Future<String> first = executor.submit(approve);
            Future<String> second = executor.submit(reject);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("decided", "BOOKING_EXTENSION_STATE_CONFLICT");
        } finally {
            executor.shutdownNow();
        }
        assertThat(extensions.findById(pending.extensionId()).orElseThrow().getStatus())
                .isIn(BookingExtensionStatus.APPROVED, BookingExtensionStatus.REJECTED);
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getEndAt())
                .isIn(END, END.plusSeconds(3_600));
    }

    @Test
    void requesterMayCancelOnlyPendingExtensionAndHistoryIsRetained() throws SQLException {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        UUID requesterId = reservation.getOrganizerUserId();
        when(authorizationClient.authorizeCurrentCaller(any(BookingAuthorizationRequest.class)))
                .thenReturn(requesterId);
        configureOwnerValidation(reservation.getBookingRequest(), 5);
        var pending = extensionService.request(reservation.getId(),
                new CreateBookingExtensionRequest(END.plusSeconds(3_600)), null);

        var cancelled = extensionService.cancel(pending.extensionId());

        assertThat(cancelled.status()).isEqualTo(BookingExtensionStatus.CANCELLED);
        assertThat(cancelled.decidedByUserId()).isEqualTo(requesterId);
        assertThat(cancelled.decidedAt()).isNotNull();
        assertThat(extensions.findById(pending.extensionId())).isPresent();
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getEndAt()).isEqualTo(END);
    }

    @Test
    void adjacentBufferedReservationRangesRemainValid() throws SQLException {
        UUID roomId = UUID.randomUUID();
        Reservation first = confirmedReservation(UUID.randomUUID(), roomId, START, END);
        Instant secondStart = END.plusSeconds(300);

        Reservation adjacent = confirmedReservation(UUID.randomUUID(), roomId, secondStart,
                secondStart.plusSeconds(3_600));

        assertThat(first.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(adjacent.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservationOccurrences.findAllByReservation_IdOrderByStartAt(adjacent.getId()))
                .singleElement()
                .satisfies(occurrence -> assertThat(occurrence.getStartAt()).isEqualTo(secondStart));
    }

    @Test
    void rejectionCommitsOnlyTheDecisionAndRejectedRequest() {
        BookingRequest request = pendingRequest(UUID.randomUUID(), UUID.randomUUID(), START, END);
        configureAuthorization(request, UUID.randomUUID());

        var response = decisionService.decide(request.getId(),
                new BookingDecisionRequest(ApprovalDecisionType.REJECTED, null));

        assertThat(response.requestStatus()).isEqualTo(BookingRequestStatus.REJECTED);
        assertThat(bookingRequests.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingRequestStatus.REJECTED);
        assertThat(decisions.existsByBookingRequest_Id(request.getId())).isTrue();
        assertThat(reservations.existsByBookingRequest_Id(request.getId())).isFalse();
        assertThat(reservationOccurrences.findAllByBookingRequestId(request.getId())).isEmpty();
        assertThat(meetingsForRequest(request.getId())).isZero();
        assertThat(participantsForRequest(request.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM booking_event_outbox WHERE aggregate_id IN (
                    SELECT id FROM reservation WHERE booking_request_id = ?
                )
                """, Integer.class, request.getId())).isZero();
        assertThat(reservationOccurrences.findAllByBookingRequestId(request.getId())).isEmpty();
    }

    @Test
    void databaseFailureAfterReservationAndMeetingWritesRollsBackEveryApprovalRecord() {
        BookingRequest request = pendingRequest(UUID.randomUUID(), UUID.randomUUID(), START, END);
        configureAuthorization(request, UUID.randomUUID());
        configureOwnerValidation(request, 5);
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION booking_test_fail_participant() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'test_participant_failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_booking_test_fail_participant
                BEFORE INSERT ON meeting_participant
                FOR EACH ROW EXECUTE FUNCTION booking_test_fail_participant()
                """);

        assertThatThrownBy(() -> decisionService.decide(request.getId(),
                new BookingDecisionRequest(ApprovalDecisionType.APPROVED, "Must roll back")))
                .isInstanceOf(RuntimeException.class);

        assertThat(bookingRequests.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingRequestStatus.PENDING_APPROVAL);
        assertThat(decisions.existsByBookingRequest_Id(request.getId())).isFalse();
        assertThat(reservations.existsByBookingRequest_Id(request.getId())).isFalse();
        assertThat(meetingsForRequest(request.getId())).isZero();
        assertThat(participantsForRequest(request.getId())).isZero();
    }

    @Test
    void staleAndAlreadyDecidedRequestsCannotOverwriteDecisionHistory() {
        BookingRequest request = pendingRequest(UUID.randomUUID(), UUID.randomUUID(), START, END);
        configureAuthorization(request, UUID.randomUUID());
        decisionService.decide(request.getId(), new BookingDecisionRequest(ApprovalDecisionType.REJECTED, null));
        reset(authorizationClient, ownerServicesClient);
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("Authorization", TOKEN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));
        when(authorizationClient.authorizeApproval(request.getRequestedByUserId(), request.getOfficeBuildingId()))
                .thenReturn(new BookingAuthorizationResponse(UUID.randomUUID(),
                        request.getRequestedByUserId(), "Trusted requester"));

        assertThatThrownBy(() -> decisionService.decide(request.getId(),
                new BookingDecisionRequest(ApprovalDecisionType.APPROVED, "Late competing decision")))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException domainException = (DomainException) error;
                    assertThat(domainException.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(domainException.code()).isEqualTo("BOOKING_REQUEST_STATE_CONFLICT");
                });

        assertThat(decisionsForRequest(request.getId())).isEqualTo(1);
        assertThat(reservationsForRequest(request.getId())).isZero();
        assertThat(bookingRequests.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingRequestStatus.REJECTED);
    }

    @Test
    void identityAuthorizationFailureLeavesPendingRequestUntouched() {
        BookingRequest request = pendingRequest(UUID.randomUUID(), UUID.randomUUID(), START, END);
        when(authorizationClient.authorizeApproval(request.getRequestedByUserId(), request.getOfficeBuildingId()))
                .thenThrow(new DomainException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Denied"));

        assertThatThrownBy(() -> decisionService.decide(request.getId(),
                new BookingDecisionRequest(ApprovalDecisionType.APPROVED, null)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).status()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(decisionsForRequest(request.getId())).isZero();
        assertThat(reservationsForRequest(request.getId())).isZero();
        assertThat(bookingRequests.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingRequestStatus.PENDING_APPROVAL);
    }

    @Test
    void concurrentCompetingDecisionsOnlyCommitOneDecision() throws Exception {
        BookingRequest request = pendingRequest(UUID.randomUUID(), UUID.randomUUID(), START, END);
        configureAuthorization(request, UUID.randomUUID());
        configureOwnerValidation(request, 5);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> decide = () -> {
            MockHttpServletRequest httpRequest = new MockHttpServletRequest();
            httpRequest.addHeader("Authorization", TOKEN);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            try {
                decisionService.decide(request.getId(),
                        new BookingDecisionRequest(ApprovalDecisionType.APPROVED, null));
                return "approved";
            } catch (DomainException exception) {
                return exception.code();
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }

        };
        try {
            Future<String> first = executor.submit(decide);
            Future<String> second = executor.submit(decide);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("approved", "BOOKING_REQUEST_STATE_CONFLICT");
        } finally {
            executor.shutdownNow();
        }
        assertThat(decisionsForRequest(request.getId())).isEqualTo(1);
        assertThat(reservationsForRequest(request.getId())).isEqualTo(1);
    }

    @Test
    void recurringCancellationReleasesAllOccurrenceOccupancyAndAllowsRebooking() throws SQLException {
        UUID roomId = UUID.randomUUID();
        UUID organizerId = UUID.randomUUID();
        BookingRequest request = pendingRecurringRequest(organizerId, roomId, UUID.randomUUID(), START, END);
        request.approve();
        bookingRequests.saveAndFlush(request);
        PGobject occupied = occupiedPeriod(START, END.plusSeconds(300));
        Reservation reservation = reservations.saveAndFlush(new Reservation(request, roomId, organizerId,
                request.getRecurrenceRuleId(), occupied, START, END, 5, ReservationStatus.CONFIRMED, Instant.now()));
        reservationOccurrences.saveAndFlush(new ReservationOccurrence(reservation, roomId, START, END, occupied));
        reservationOccurrences.saveAndFlush(new ReservationOccurrence(reservation, roomId,
                START.plusSeconds(86_400), END.plusSeconds(86_400),
                occupiedPeriod(START.plusSeconds(86_400), END.plusSeconds(86_700))));
        configureCancellationAuthorization(organizerId, request.getOfficeBuildingId(),
                BookingAuthorizationResponse.CancellationAuthority.OWNER);
        when(ownerServicesClient.cancellationDeadlineAt(roomId, request.getOfficeBuildingId(), START, TOKEN))
                .thenReturn(null);

        var result = cancellationService.cancelReservation(reservation.getId(),
                new CancellationRequest(CancellationReason.USER, "Organizer cancelled", null));

        assertThat(result.reservationStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(result.bookingRequestStatus()).isEqualTo(BookingRequestStatus.CANCELLED);
        assertThat(result.overrideApplied()).isFalse();
        assertThat(reservationOccurrences.findAllByReservation_IdOrderByStartAt(reservation.getId()))
                .hasSize(2)
                .allSatisfy(occurrence -> assertThat(occurrence.isOccupancyActive()).isFalse());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM booking_event_outbox
                WHERE aggregate_id = ? AND payload ->> 'action' = 'REMOVE'
                """, Integer.class, reservation.getId())).isEqualTo(2);
        assertThat(reservations.existsRoomOccupancyConflict(roomId, START, END.plusSeconds(300))).isFalse();
        assertThat(reservationOccurrences.existsRoomOccupancyConflict(roomId, START, END.plusSeconds(300)))
                .isFalse();
        assertThat(confirmedReservation(UUID.randomUUID(), roomId, START, END).getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void cancellationDeadlineRequiresAnAdministratorOverrideAndPersistsItsSnapshot() throws SQLException {
        UUID roomId = UUID.randomUUID();
        Reservation reservation = confirmedReservation(UUID.randomUUID(), roomId, START, END);
        BookingRequest request = reservation.getBookingRequest();
        UUID adminId = UUID.randomUUID();
        configureCancellationAuthorization(request.getRequestedByUserId(), request.getOfficeBuildingId(),
                BookingAuthorizationResponse.CancellationAuthority.ADMIN, adminId);
        Instant deadline = Instant.now().minusSeconds(600);
        when(ownerServicesClient.cancellationDeadlineAt(roomId, request.getOfficeBuildingId(), START, TOKEN))
                .thenReturn(deadline);

        assertThatThrownBy(() -> cancellationService.cancelReservation(reservation.getId(),
                new CancellationRequest(CancellationReason.ADMIN, "Admin cancellation", null)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).code())
                        .isEqualTo("CANCELLATION_DEADLINE_EXCEEDED"));
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(cancellations.findFirstByReservation_IdOrderByCancelledAtDesc(reservation.getId())).isEmpty();

        var result = cancellationService.cancelReservation(reservation.getId(),
                new CancellationRequest(CancellationReason.ADMIN, "Admin cancellation", "Urgent operational need"));
        assertThat(result.overrideApplied()).isTrue();
        assertThat(result.overrideReason()).isEqualTo("Urgent operational need");
        assertThat(result.cancellationDeadlineAt()).isEqualTo(deadline);
        assertThat(result.cancelledByUserId()).isEqualTo(adminId);
        assertThat(result.reservationStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void ownerCannotUseAdministrativeReasonOrOverrideAnExpiredDeadline() throws SQLException {
        UUID roomId = UUID.randomUUID();
        Reservation reservation = confirmedReservation(UUID.randomUUID(), roomId, START, END);
        BookingRequest request = reservation.getBookingRequest();
        configureCancellationAuthorization(request.getRequestedByUserId(), request.getOfficeBuildingId(),
                BookingAuthorizationResponse.CancellationAuthority.OWNER);
        when(ownerServicesClient.cancellationDeadlineAt(roomId, request.getOfficeBuildingId(), START, TOKEN))
                .thenReturn(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> cancellationService.cancelReservation(reservation.getId(),
                new CancellationRequest(CancellationReason.ADMIN, "Admin cancellation", "I am admin")))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).code())
                        .isEqualTo("CANCELLATION_REASON_INVALID"));
        assertThatThrownBy(() -> cancellationService.cancelReservation(reservation.getId(),
                new CancellationRequest(CancellationReason.USER, "Late owner cancellation", null)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).code())
                        .isEqualTo("CANCELLATION_DEADLINE_EXCEEDED"));
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(cancellations.findFirstByReservation_IdOrderByCancelledAtDesc(reservation.getId())).isEmpty();
    }

    @Test
    void cancellationDatabaseFailureRollsBackStatesHistoryAndOccurrenceRelease() throws SQLException {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        BookingRequest request = reservation.getBookingRequest();
        configureCancellationAuthorization(request.getRequestedByUserId(), request.getOfficeBuildingId(),
                BookingAuthorizationResponse.CancellationAuthority.OWNER);
        when(ownerServicesClient.cancellationDeadlineAt(reservation.getRoomId(), request.getOfficeBuildingId(),
                START, TOKEN)).thenReturn(null);
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION booking_test_fail_cancellation() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'test_cancellation_failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_booking_test_fail_cancellation
                BEFORE INSERT ON cancellation
                FOR EACH ROW EXECUTE FUNCTION booking_test_fail_cancellation()
                """);

        assertThatThrownBy(() -> cancellationService.cancelReservation(reservation.getId(),
                new CancellationRequest(CancellationReason.USER, "Rollback test", null)))
                .isInstanceOf(RuntimeException.class);
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(bookingRequests.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingRequestStatus.APPROVED);
        assertThat(cancellations.findFirstByReservation_IdOrderByCancelledAtDesc(reservation.getId())).isEmpty();
        assertThat(reservationOccurrences.findAllByReservation_IdOrderByStartAt(reservation.getId()))
                .allSatisfy(occurrence -> assertThat(occurrence.isOccupancyActive()).isTrue());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM booking_event_outbox WHERE aggregate_id = ?
                """, Integer.class, reservation.getId())).isZero();
    }

    @Test
    void concurrentCancellationRequestsPersistOneHistoryRecordAndReleaseOnce() throws Exception {
        Reservation reservation = confirmedReservation(UUID.randomUUID(), UUID.randomUUID(), START, END);
        BookingRequest request = reservation.getBookingRequest();
        configureCancellationAuthorization(request.getRequestedByUserId(), request.getOfficeBuildingId(),
                BookingAuthorizationResponse.CancellationAuthority.OWNER);
        when(ownerServicesClient.cancellationDeadlineAt(reservation.getRoomId(), request.getOfficeBuildingId(),
                START, TOKEN)).thenReturn(null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<UUID> cancel = () -> {
            MockHttpServletRequest httpRequest = new MockHttpServletRequest();
            httpRequest.addHeader("Authorization", TOKEN);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            try {
                return cancellationService.cancelReservation(reservation.getId(),
                        new CancellationRequest(CancellationReason.USER, "Concurrent cancellation", null))
                        .cancellationId();
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        };
        try {
            Future<UUID> first = executor.submit(cancel);
            Future<UUID> second = executor.submit(cancel);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertThat(cancellations.findAll().stream()
                .filter(cancellation -> reservation.getId().equals(
                        cancellation.getReservation() == null ? null : cancellation.getReservation().getId())))
                .hasSize(1);
        assertThat(reservationOccurrences.findAllByReservation_IdOrderByStartAt(reservation.getId()))
                .allSatisfy(occurrence -> assertThat(occurrence.isOccupancyActive()).isFalse());
    }

    @Test
    void pendingRequestCancellationPreservesRequestAndIsIdempotent() {
        BookingRequest request = pendingRequest(UUID.randomUUID(), UUID.randomUUID(), START, END);
        configureCancellationAuthorization(request.getRequestedByUserId(), request.getOfficeBuildingId(),
                BookingAuthorizationResponse.CancellationAuthority.OWNER);
        CancellationRequest cancellationRequest = new CancellationRequest(
                CancellationReason.USER, "No longer required", null);

        var first = cancellationService.cancelRequest(request.getId(), cancellationRequest);
        var replay = cancellationService.cancelRequest(request.getId(), cancellationRequest);

        assertThat(first.cancellationId()).isEqualTo(replay.cancellationId());
        assertThatThrownBy(() -> cancellationService.cancelRequest(request.getId(),
                new CancellationRequest(CancellationReason.USER, "Different reason", null)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).code())
                        .isEqualTo("CANCELLATION_REPLAY_CONFLICT"));
        assertThat(bookingRequests.findById(request.getId())).isPresent()
                .get().extracting(BookingRequest::getStatus).isEqualTo(BookingRequestStatus.CANCELLED);
        assertThat(cancellations.findFirstByBookingRequest_IdOrderByCancelledAtDesc(request.getId())).isPresent();
    }

    @Test
    void concurrentRoomConfirmationsCannotBothOccupyTheSameInterval() throws Exception {
        UUID sharedRoomId = UUID.randomUUID();
        BookingRequest firstRequest = pendingRequest(UUID.randomUUID(), sharedRoomId, START, END);
        BookingRequest secondRequest = pendingRequest(UUID.randomUUID(), sharedRoomId, START, END);
        CyclicBarrier ownerValidationBarrier = new CyclicBarrier(2);
        when(authorizationClient.authorizeApproval(any(UUID.class), any(UUID.class)))
                .thenAnswer(invocation -> new BookingAuthorizationResponse(
                        UUID.randomUUID(), invocation.getArgument(0), "Trusted requester"));
        doAnswer(invocation -> {
            BookingRequestFacts facts = invocation.getArgument(0);
            ownerValidationBarrier.await(10, TimeUnit.SECONDS);
            return references(facts, 5);
        }).when(ownerServicesClient).validateRequest(any(BookingRequestFacts.class), eq(TOKEN), any(Instant.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> approveRequest(decisionService, firstRequest, ready, start));
            Future<String> second = executor.submit(() -> approveRequest(decisionService, secondRequest, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("approved", "ROOM_OCCUPANCY_CONFLICT");
        } finally {
            executor.shutdownNow();
        }
        assertThat(decisionsForRequest(firstRequest.getId())
                + decisionsForRequest(secondRequest.getId())).isEqualTo(1);
        assertThat(reservationsForRequest(firstRequest.getId())
                + reservationsForRequest(secondRequest.getId())).isEqualTo(1);
        assertThat(occurrencesForRequest(firstRequest.getId())
                + occurrencesForRequest(secondRequest.getId())).isEqualTo(1);
        assertThat(List.of(bookingRequests.findById(firstRequest.getId()).orElseThrow().getStatus(),
                bookingRequests.findById(secondRequest.getId()).orElseThrow().getStatus()))
                .containsExactlyInAnyOrder(BookingRequestStatus.APPROVED, BookingRequestStatus.PENDING_APPROVAL);
    }

    @Test
    void concurrentRecurringConfirmationsCannotPartiallyOccupyTheSameSeriesDates() throws Exception {
        UUID sharedRoomId = UUID.randomUUID();
        BookingRequest firstRequest = pendingRecurringRequest(UUID.randomUUID(), sharedRoomId,
                UUID.randomUUID(), START, END);
        BookingRequest secondRequest = pendingRecurringRequest(UUID.randomUUID(), sharedRoomId,
                UUID.randomUUID(), START, END);
        CyclicBarrier ownerValidationBarrier = new CyclicBarrier(2);
        when(authorizationClient.authorizeApproval(any(UUID.class), any(UUID.class)))
                .thenAnswer(invocation -> new BookingAuthorizationResponse(
                        UUID.randomUUID(), invocation.getArgument(0), "Trusted requester"));
        doAnswer(invocation -> {
            BookingRequestFacts facts = invocation.getArgument(0);
            ownerValidationBarrier.await(10, TimeUnit.SECONDS);
            Instant nextStart = facts.startsAt().plusSeconds(86_400);
            return recurrenceReferences(facts, List.of(
                    new SchedulingOccurrence(LocalDate.parse("2030-04-10"), facts.startsAt(), facts.endsAt()),
                    new SchedulingOccurrence(LocalDate.parse("2030-04-11"), nextStart,
                            facts.endsAt().plusSeconds(86_400))));
        }).when(ownerServicesClient).validateRequest(any(BookingRequestFacts.class), eq(TOKEN), any(Instant.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> approveRequest(decisionService, firstRequest, ready, start));
            Future<String> second = executor.submit(() -> approveRequest(decisionService, secondRequest, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("approved", "ROOM_OCCUPANCY_CONFLICT");
        } finally {
            executor.shutdownNow();
        }

        assertThat(reservationsForRequest(firstRequest.getId())
                + reservationsForRequest(secondRequest.getId())).isEqualTo(1);
        assertThat(occurrencesForRequest(firstRequest.getId())
                + occurrencesForRequest(secondRequest.getId())).isEqualTo(2);
        assertThat(decisionsForRequest(firstRequest.getId())
                + decisionsForRequest(secondRequest.getId())).isEqualTo(1);
        assertThat(List.of(bookingRequests.findById(firstRequest.getId()).orElseThrow().getStatus(),
                bookingRequests.findById(secondRequest.getId()).orElseThrow().getStatus()))
                .containsExactlyInAnyOrder(BookingRequestStatus.APPROVED, BookingRequestStatus.PENDING_APPROVAL);
    }

    @Test
    void laterRecurrenceOccurrenceConflictIsRejectedWithoutPartialApproval() {
        UUID sharedRoomId = UUID.randomUUID();
        Instant secondDayStart = START.plusSeconds(86_400);
        Instant thirdDayStart = START.plusSeconds(172_800);
        BookingRequest firstRequest = pendingRecurringRequest(UUID.randomUUID(), sharedRoomId,
                UUID.randomUUID(), START, END);
        BookingRequest secondRequest = pendingRecurringRequest(UUID.randomUUID(), sharedRoomId,
                UUID.randomUUID(), secondDayStart, secondDayStart.plusSeconds(3_600));
        configureAuthorization(firstRequest, UUID.randomUUID());
        configureAuthorization(secondRequest, UUID.randomUUID());
        when(ownerServicesClient.validateRequest(any(BookingRequestFacts.class), eq(TOKEN), any(Instant.class)))
                .thenAnswer(invocation -> {
                    BookingRequestFacts facts = invocation.getArgument(0);
                    Instant nextStart = facts.startsAt().plusSeconds(86_400);
                    Instant nextEnd = facts.endsAt().plusSeconds(86_400);
                    return recurrenceReferences(facts, List.of(
                            new SchedulingOccurrence(
                                    facts.startsAt().atZone(ZoneId.of("Africa/Kigali")).toLocalDate(),
                                    facts.startsAt(), facts.endsAt()),
                            new SchedulingOccurrence(
                                    nextStart.atZone(ZoneId.of("Africa/Kigali")).toLocalDate(),
                                    nextStart, nextEnd)));
                });

        decisionService.decide(firstRequest.getId(),
                new BookingDecisionRequest(ApprovalDecisionType.APPROVED, null));
        assertThat(occurrencesForRequest(firstRequest.getId())).isEqualTo(2);

        assertThatThrownBy(() -> decisionService.decide(secondRequest.getId(),
                new BookingDecisionRequest(ApprovalDecisionType.APPROVED, null)))
                .isInstanceOf(DomainException.class)
                .satisfies(exception -> {
                    DomainException domainException = (DomainException) exception;
                    assertThat(domainException.code()).isEqualTo("ROOM_OCCUPANCY_CONFLICT");
                    assertThat(domainException.getMessage()).contains("2030-04-11");
                });

        assertThat(bookingRequests.findById(secondRequest.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingRequestStatus.PENDING_APPROVAL);
        assertThat(decisionsForRequest(secondRequest.getId())).isZero();
        assertThat(reservationsForRequest(secondRequest.getId())).isZero();
        assertThat(occurrencesForRequest(secondRequest.getId())).isZero();
    }

    @Test
    void recurringApprovalPersistsTheWholeSeriesAndMeetingAtomically() {
        BookingRequest request = pendingRecurringRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), START, END);
        configureAuthorization(request, UUID.randomUUID());
        Instant secondStart = START.plusSeconds(86_400);
        when(ownerServicesClient.validateRequest(any(BookingRequestFacts.class), eq(TOKEN), any(Instant.class)))
                .thenAnswer(invocation -> {
                    BookingRequestFacts facts = invocation.getArgument(0);
                    return recurrenceReferences(facts, List.of(
                            new SchedulingOccurrence(LocalDate.parse("2030-04-10"), facts.startsAt(), facts.endsAt()),
                            new SchedulingOccurrence(LocalDate.parse("2030-04-11"), secondStart,
                                    secondStart.plusSeconds(3_600))));
                });

        decisionService.decide(request.getId(),
                new BookingDecisionRequest(ApprovalDecisionType.APPROVED, "series approved"));

        assertThat(bookingRequests.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingRequestStatus.APPROVED);
        assertThat(decisionsForRequest(request.getId())).isEqualTo(1);
        assertThat(reservationsForRequest(request.getId())).isEqualTo(1);
        assertThat(meetingsForRequest(request.getId())).isEqualTo(1);
        assertThat(participantsForRequest(request.getId())).isEqualTo(1);
        assertThat(reservationOccurrences.findAllByBookingRequestId(request.getId()))
                .extracting(ReservationOccurrence::getStartAt)
                .containsExactly(START, secondStart);
    }

    @Test
    void aNewReservationMayStartExactlyAtThePriorReleaseBufferBoundary() {
        UUID sharedRoomId = UUID.randomUUID();
        BookingRequest firstRequest = pendingRequest(UUID.randomUUID(), sharedRoomId, START, END);
        Instant secondStart = END.plusSeconds(15 * 60L);
        BookingRequest secondRequest = pendingRequest(UUID.randomUUID(), sharedRoomId,
                secondStart, secondStart.plusSeconds(3_600));
        configureAuthorization(firstRequest, UUID.randomUUID());
        configureAuthorization(secondRequest, UUID.randomUUID());
        configureOwnerValidation(firstRequest, 15);
        configureOwnerValidation(secondRequest, 15);

        decisionService.decide(firstRequest.getId(),
                new BookingDecisionRequest(ApprovalDecisionType.APPROVED, null));
        decisionService.decide(secondRequest.getId(),
                new BookingDecisionRequest(ApprovalDecisionType.APPROVED, null));

        assertThat(reservationsForRequest(firstRequest.getId())).isEqualTo(1);
        assertThat(reservationsForRequest(secondRequest.getId())).isEqualTo(1);
        assertThat(occurrencesForRequest(firstRequest.getId())).isEqualTo(1);
        assertThat(occurrencesForRequest(secondRequest.getId())).isEqualTo(1);
    }

    private String approveRequest(BookingDecisionService service, BookingRequest request,
                                 CountDownLatch ready, CountDownLatch start) throws Exception {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("Authorization", TOKEN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        try {
            service.decide(request.getId(), new BookingDecisionRequest(ApprovalDecisionType.APPROVED, null));
            return "approved";
        } catch (DomainException exception) {
            return exception.code();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private String decideExtension(BookingExtensionService service, UUID extensionId,
                                   BookingExtensionDecisionRequest request,
                                   CountDownLatch ready, CountDownLatch start) throws Exception {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("Authorization", TOKEN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        try {
            service.decide(extensionId, request);
            return "decided";
        } catch (DomainException exception) {
            return exception.code();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private BookingExtensionResponse requestExtension(UUID reservationId, CreateBookingExtensionRequest request,
                                                       CountDownLatch ready, CountDownLatch start) throws Exception {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("Authorization", TOKEN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent extension retry test did not start");
            }
            return extensionService.request(reservationId, request, "concurrent-extension-key");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private String createBookingRequest(CreateBookingRequest request, CountDownLatch ready,
                                        CountDownLatch start) throws Exception {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("Authorization", TOKEN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent booking request retry test did not start");
            }
            return bookingRequestService.create(request, "concurrent-request-key").requestReference();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private BookingRequest pendingRequest(UUID requesterId, UUID roomId, Instant startsAt, Instant endsAt) {
        BookingRequest request = new BookingRequest("BR-" + UUID.randomUUID(), BookingRequestType.SECRETARY_REQUEST,
                requesterId, UUID.randomUUID(), roomId, UUID.randomUUID(), null, "Test meeting", "Test agenda",
                startsAt, endsAt, 3, false, BookingRequestStatus.PENDING_APPROVAL, null, Instant.now());
        return bookingRequests.saveAndFlush(request);
    }

    private BookingRequest pendingRecurringRequest(UUID requesterId, UUID roomId, UUID recurrenceRuleId,
                                                   Instant startsAt, Instant endsAt) {
        BookingRequest request = new BookingRequest("BR-" + UUID.randomUUID(),
                BookingRequestType.SECRETARY_REQUEST, requesterId, UUID.randomUUID(), roomId, UUID.randomUUID(),
                recurrenceRuleId, "Test meeting", "Test agenda", startsAt, endsAt, 3, false,
                BookingRequestStatus.PENDING_APPROVAL, null, Instant.now());
        return bookingRequests.saveAndFlush(request);
    }

    private Reservation confirmedReservation(UUID organizerId, UUID roomId, Instant startsAt, Instant endsAt)
            throws SQLException {
        BookingRequest request = pendingRequest(organizerId, roomId, startsAt, endsAt);
        request.approve();
        bookingRequests.saveAndFlush(request);
        PGobject occupied = occupiedPeriod(startsAt, endsAt.plusSeconds(300));
        Reservation reservation = reservations.saveAndFlush(new Reservation(request, roomId, organizerId, null, occupied,
                startsAt, endsAt, 5, ReservationStatus.CONFIRMED, Instant.now()));
        reservationOccurrences.saveAndFlush(new ReservationOccurrence(reservation, roomId, startsAt, endsAt, occupied));
        meetings.saveAndFlush(new Meeting(reservation, "Test meeting", null, "Test organizer",
                null, null, MeetingVisibility.INTERNAL, Instant.now()));
        return reservation;
    }

    private void setRequestBuilding(Reservation reservation, UUID buildingId) {
        jdbcTemplate.update("UPDATE booking_request SET office_building_id = ? WHERE id = ?",
                buildingId, reservation.getBookingRequest().getId());
    }

    private static PGobject occupiedPeriod(Instant startsAt, Instant occupiedUntil) throws SQLException {
        PGobject occupied = new PGobject();
        occupied.setType("tstzrange");
        occupied.setValue("[" + startsAt + "," + occupiedUntil + ")");
        return occupied;
    }

    private static void assertLifecycleConflict(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException exception = (DomainException) error;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("RESERVATION_STATE_CONFLICT");
                });
    }

    private static void assertExclusionViolation(Throwable failure, String constraintName) {
        SQLException postgresException = null;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                postgresException = sqlException;
                break;
            }
        }
        assertThat(postgresException != null).isTrue();
        assertThat(postgresException.getSQLState()).isEqualTo("23P01");
        assertThat(postgresException.getMessage()).contains(constraintName);
    }

    private void configureAuthorization(BookingRequest request, UUID actorId) {
        when(authorizationClient.authorizeApproval(request.getRequestedByUserId(), request.getOfficeBuildingId()))
                .thenReturn(new BookingAuthorizationResponse(actorId,
                        request.getRequestedByUserId(), "Trusted requester"));
    }

    private void configureCancellationAuthorization(UUID ownerId, UUID buildingId,
                                                    BookingAuthorizationResponse.CancellationAuthority authority) {
        configureCancellationAuthorization(ownerId, buildingId, authority, ownerId);
    }

    private void configureCancellationAuthorization(UUID ownerId, UUID buildingId,
                                                    BookingAuthorizationResponse.CancellationAuthority authority,
                                                    UUID actorId) {
        when(authorizationClient.authorizeCancellation(ownerId, buildingId))
                .thenReturn(new BookingAuthorizationResponse(actorId, ownerId, null, null, authority));
        when(authorizationClient.currentBearerToken()).thenReturn(TOKEN);
    }

    private void configureExtensionAuthorization(Reservation reservation, UUID decisionActorId) {
        UUID requesterId = reservation.getBookingRequest().getRequestedByUserId();
        when(authorizationClient.authorizeCurrentCaller(any(BookingAuthorizationRequest.class)))
                .thenAnswer(invocation -> {
                    BookingAuthorizationRequest request = invocation.getArgument(0);
                    return switch (request.action()) {
                        case EXTENSION_REQUEST -> requesterId;
                        case EXTENSION_DECIDE -> decisionActorId;
                        default -> null;
                    };
                });
    }

    private void configureOwnerValidation(BookingRequest request, int releaseBufferMinutes) {
        when(ownerServicesClient.validateRequest(any(BookingRequestFacts.class), eq(TOKEN), any(Instant.class)))
                .thenAnswer(invocation -> references(invocation.getArgument(0), releaseBufferMinutes));
        when(ownerServicesClient.validateExtension(any(BookingRequestFacts.class), eq(TOKEN), any(Instant.class)))
                .thenAnswer(invocation -> references(invocation.getArgument(0), releaseBufferMinutes));
    }

    private ValidatedBookingReferences references(BookingRequestFacts facts, int releaseBufferMinutes) {
        LocalDate occurrenceDate = facts.startsAt().atZone(ZoneId.of("Africa/Kigali")).toLocalDate();
        return recurrenceReferences(facts, List.of(
                new SchedulingOccurrence(occurrenceDate, facts.startsAt(), facts.endsAt())), releaseBufferMinutes);
    }

    private ValidatedBookingReferences recurrenceReferences(BookingRequestFacts facts,
                                                             List<SchedulingOccurrence> intervals) {
        return recurrenceReferences(facts, intervals, 5);
    }

    private ValidatedBookingReferences recurrenceReferences(BookingRequestFacts facts,
                                                             List<SchedulingOccurrence> intervals,
                                                             int releaseBufferMinutes) {
        return new ValidatedBookingReferences(facts.officeBuildingId(), facts.departmentId(), facts.roomId(),
                false, "Africa/Kigali", true, releaseBufferMinutes, intervals);
    }

    private long decisionsForRequest(UUID requestId) {
        return decisions.findAll().stream()
                .filter(decision -> decision.getBookingRequest().getId().equals(requestId))
                .count();
    }

    private long reservationsForRequest(UUID requestId) {
        return reservations.findAll().stream()
                .filter(reservation -> reservation.getBookingRequest().getId().equals(requestId))
                .count();
    }

    private long meetingsForRequest(UUID requestId) {
        return meetings.findAll().stream()
                .filter(meeting -> meeting.getReservation().getBookingRequest().getId().equals(requestId))
                .count();
    }

    private long participantsForRequest(UUID requestId) {
        return participants.findAll().stream()
                .filter(participant -> participant.getMeeting().getReservation().getBookingRequest().getId()
                        .equals(requestId))
                .count();
    }

    private long occurrencesForRequest(UUID requestId) {
        return reservationOccurrences.findAllByBookingRequestId(requestId).size();
    }

    @TestConfiguration
    static class TestClients {
        @Bean
        @Primary
        BookingAuthorizationClient testBookingAuthorizationClient() {
            return org.mockito.Mockito.mock(BookingAuthorizationClient.class);
        }

        @Bean
        @Primary
        BookingOwnerServicesClient testBookingOwnerServicesClient() {
            return org.mockito.Mockito.mock(BookingOwnerServicesClient.class);
        }
    }
}
