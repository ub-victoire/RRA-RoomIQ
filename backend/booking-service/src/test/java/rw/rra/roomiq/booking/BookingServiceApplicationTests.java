package rw.rra.roomiq.booking;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.postgresql.util.PGobject;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import rw.rra.roomiq.booking.domain.entity.ApprovalDecision;
import rw.rra.roomiq.booking.domain.entity.BookingExtension;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;
import rw.rra.roomiq.booking.domain.entity.Cancellation;
import rw.rra.roomiq.booking.domain.entity.Meeting;
import rw.rra.roomiq.booking.domain.entity.MeetingParticipant;
import rw.rra.roomiq.booking.domain.entity.MeetingShareLink;
import rw.rra.roomiq.booking.domain.entity.Reservation;
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
import rw.rra.roomiq.booking.domain.repository.BookingRequestRepository;
import rw.rra.roomiq.booking.domain.repository.CancellationRepository;
import rw.rra.roomiq.booking.domain.repository.MeetingParticipantRepository;
import rw.rra.roomiq.booking.domain.repository.MeetingRepository;
import rw.rra.roomiq.booking.domain.repository.MeetingShareLinkRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationOccurrenceRepository;
import rw.rra.roomiq.booking.domain.service.BookingOccupancyService;
import rw.rra.roomiq.booking.domain.dto.BookingOccupancyRequest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class BookingServiceApplicationTests {
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private BookingRequestRepository bookingRequestRepository;

    @Autowired
    private ApprovalDecisionRepository approvalDecisionRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationOccurrenceRepository reservationOccurrenceRepository;

    @Autowired
    private MeetingRepository meetingRepository;

    @Autowired
    private MeetingParticipantRepository meetingParticipantRepository;

    @Autowired
    private MeetingShareLinkRepository meetingShareLinkRepository;

    @Autowired
    private BookingExtensionRepository bookingExtensionRepository;

    @Autowired
    private CancellationRepository cancellationRepository;

    @Autowired
    private BookingOccupancyService bookingOccupancyService;

    @Test
    void contextLoads() {
    }

    @Test
    void onlyApprovedBookingAndMeetingApiRoutesAreExposedAndOtherBusinessRoutesRemainUnavailable() {
        Set<String> apiRoutes = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .filter(path -> path.startsWith("/api/v1/"))
                .collect(Collectors.toSet());

        assertThat(apiRoutes).containsExactlyInAnyOrder(
                "/api/v1/booking-requests",
                "/api/v1/booking-requests/{id}/submit",
                "/api/v1/booking-requests/{id}/decision",
                "/api/v1/booking-requests/{requestId}/cancel",
                "/api/v1/booking-requests/{id}",
                "/api/v1/bookings/direct",
                "/api/v1/reservations/{reservationId}/check-in",
                "/api/v1/reservations/{reservationId}/complete",
                "/api/v1/reservations/{reservationId}/release",
                "/api/v1/reservations/{reservationId}/cancel",
                "/api/v1/reservations/{reservationId}/extensions",
                "/api/v1/booking-extensions/{extensionId}/decision",
                "/api/v1/booking-extensions/{extensionId}/cancel",
                "/api/v1/meetings/{meetingId}",
                "/api/v1/meetings/{meetingId}/participants",
                "/api/v1/meetings/{meetingId}/participants/{participantId}",
                "/api/v1/meetings/{meetingId}/share-links",
                "/api/v1/meeting-share-links/{shareLinkId}/revoke",
                "/api/v1/internal/availability/occupancy",
                "/api/v1/internal/notifications/room-event-recipients",
                "/api/v1/public/meetings/{token}");
    }

    @Test
    void flywayCreatesOnlyBookingOwnedTablesAndRequiredIntegrity() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
                Integer.class)).isEqualTo(1);
        assertThat(tableNames()).contains("booking_request", "approval_decision", "reservation", "meeting",
                "meeting_participant", "meeting_share_link", "booking_extension", "cancellation",
                "reservation_occurrence", "booking_event_outbox")
                .doesNotContain("room", "app_user", "office_building", "recurrence_rule");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = TRUE",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success = TRUE",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '5' AND success = TRUE",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '6' AND success = TRUE",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '7' AND success = TRUE",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint
                WHERE conname IN ('ck_booking_request_type', 'ck_booking_request_status',
                    'ck_booking_request_interval', 'ck_booking_request_attendee_count',
                    'ck_approval_decision_decision', 'ck_reservation_status',
                    'ck_meeting_visibility', 'ck_meeting_participant_identity',
                    'ck_meeting_participant_role', 'ck_meeting_participant_invite_status',
                    'ck_booking_extension_status', 'ck_booking_extension_requested_end',
                    'ck_cancellation_reason_code', 'ck_cancellation_override_reason',
                    'ck_reservation_completed_at_status')
                """, Integer.class)).isEqualTo(15);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint
                WHERE conname = 'ex_reservation_room_occupied_period' AND contype = 'x'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint
                WHERE conname = 'ex_reservation_occurrence_room_occupied_period' AND contype = 'x'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'btree_gist'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'reservation' AND column_name = 'occupied_period'
                """, String.class)).isEqualTo("tstzrange");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT column_default FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'reservation' AND column_name = 'release_buffer_minutes'
                """, String.class)).contains("5");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'meeting_share_link' AND column_name = 'timezone'
                """, String.class)).isEqualTo("NO");
    }

    @Test
    void onlyBookingOwnedRelationshipsHaveForeignKeys() throws SQLException {
        assertThat(importedForeignKeyColumns("booking_request")).isEmpty();
        assertThat(importedForeignKeyColumns("approval_decision")).containsExactly("booking_request_id");
        assertThat(importedForeignKeyColumns("reservation")).containsExactly("booking_request_id");
        assertThat(importedForeignKeyColumns("meeting")).containsExactly("reservation_id");
        assertThat(importedForeignKeyColumns("meeting_participant")).containsExactly("meeting_id");
        assertThat(importedForeignKeyColumns("meeting_share_link")).containsExactly("meeting_id");
        assertThat(importedForeignKeyColumns("booking_extension")).containsExactly("reservation_id");
        assertThat(importedForeignKeyColumns("cancellation"))
                .containsExactlyInAnyOrder("booking_request_id", "reservation_id");
        assertThat(importedForeignKeyColumns("reservation_occurrence")).containsExactly("reservation_id");
        assertThat(importedForeignKeyColumns("booking_event_outbox")).isEmpty();
    }

    @Test
    @Transactional
    void bookingEntitiesAndRepositoriesRoundTripAgainstMigratedSchema() throws Exception {
        assertThat(entityManagerFactory.getMetamodel().getEntities())
                .extracting(entityType -> entityType.getJavaType().getSimpleName())
                .containsExactlyInAnyOrder("BookingRequest", "ApprovalDecision", "Reservation", "Meeting",
                        "MeetingParticipant", "MeetingShareLink", "BookingExtension", "Cancellation",
                        "ReservationOccurrence", "BookingEventOutboxEntry", "BookingRecordLegalHold");

        Instant start = Instant.parse("2026-11-07T10:00:00Z");
        Instant end = Instant.parse("2026-11-07T11:00:00Z");
        UUID requesterId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();
        UUID recurrenceRuleId = UUID.randomUUID();
        BookingRequest request = bookingRequestRepository.saveAndFlush(new BookingRequest(
                "RRA-" + UUID.randomUUID(), BookingRequestType.SECRETARY_REQUEST, requesterId,
                departmentId, roomId, buildingId, recurrenceRuleId, "Project review",
                "Quarterly planning", start, end, 8, true, BookingRequestStatus.APPROVED,
                "create-" + UUID.randomUUID(), start.minusSeconds(3600)));

        ApprovalDecision decision = approvalDecisionRepository.saveAndFlush(new ApprovalDecision(
                request, UUID.randomUUID(), ApprovalDecisionType.APPROVED, "Approved",
                start.minusSeconds(1800)));
        PGobject occupiedPeriod = new PGobject();
        occupiedPeriod.setType("tstzrange");
        occupiedPeriod.setValue("[2026-11-07T10:00:00Z,2026-11-07T11:05:00Z)");
        Reservation reservation = reservationRepository.saveAndFlush(new Reservation(
                request, roomId, requesterId, recurrenceRuleId, occupiedPeriod, start, end, 5,
                ReservationStatus.CONFIRMED, start.minusSeconds(900)));
        PGobject occurrencePeriod = new PGobject();
        occurrencePeriod.setType("tstzrange");
        occurrencePeriod.setValue("[2026-11-07T10:00:00Z,2026-11-07T11:05:00Z)");
        ReservationOccurrence occurrence = reservationOccurrenceRepository.saveAndFlush(
                new ReservationOccurrence(reservation, roomId, start, end, occurrencePeriod));
        Meeting meeting = meetingRepository.saveAndFlush(new Meeting(
                reservation, "Project review", "Agenda", "Organizer", "Bring status notes",
                "organizer@example.test", MeetingVisibility.INTERNAL, start.minusSeconds(900)));
        MeetingParticipant participant = meetingParticipantRepository.saveAndFlush(new MeetingParticipant(
                meeting, UUID.randomUUID(), null, "Participant", ParticipantRole.REQUIRED, InviteStatus.INVITED));
        MeetingShareLink shareLink = meetingShareLinkRepository.saveAndFlush(new MeetingShareLink(
                meeting, "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                "Africa/Kigali", requesterId, null, end, null, 0));
        BookingExtension extension = bookingExtensionRepository.saveAndFlush(new BookingExtension(
                reservation, requesterId, end, end.plusSeconds(1800), null, BookingExtensionStatus.PENDING,
                null, null, "extend-" + UUID.randomUUID(), start.minusSeconds(600), null));
        Cancellation cancellation = cancellationRepository.saveAndFlush(new Cancellation(
                reservation, request, requesterId, CancellationReason.USER, "Organizer cancelled",
                start.minusSeconds(3600), false, null, start.minusSeconds(1200)));

        entityManager.clear();

        assertThat(bookingRequestRepository.findById(request.getId()).orElseThrow().getVersion()).isZero();
        assertThat(approvalDecisionRepository.findById(decision.getId()).orElseThrow()
                .getBookingRequest().getId()).isEqualTo(request.getId());
        Reservation reloadedReservation = reservationRepository.findById(reservation.getId()).orElseThrow();
        assertThat(reloadedReservation.getOccupiedPeriod().getType()).isEqualTo("tstzrange");
        assertThat(reloadedReservation.getReleaseBufferMinutes()).isEqualTo(5);
        ReservationOccurrence reloadedOccurrence =
                reservationOccurrenceRepository.findById(occurrence.getId()).orElseThrow();
        assertThat(reloadedOccurrence.getReservation().getId()).isEqualTo(reservation.getId());
        assertThat(reloadedOccurrence.getStartAt()).isEqualTo(start);
        assertThat(reloadedOccurrence.getEndAt()).isEqualTo(end);
        assertThat(reloadedOccurrence.getOccupiedPeriod().getType()).isEqualTo("tstzrange");
        assertThat(meetingRepository.findById(meeting.getId()).orElseThrow()
                .getReservation().getId()).isEqualTo(reservation.getId());
        assertThat(meetingParticipantRepository.findById(participant.getId()).orElseThrow()
                .getRole()).isEqualTo(ParticipantRole.REQUIRED);
        assertThat(meetingShareLinkRepository.findById(shareLink.getId()).orElseThrow()
                .getTokenHash()).hasSize(64);
        assertThat(meetingShareLinkRepository.findById(shareLink.getId()).orElseThrow()
                .getTimezone()).isEqualTo("Africa/Kigali");
        assertThat(bookingExtensionRepository.findById(extension.getId()).orElseThrow()
                .getRequestedEndAt()).isEqualTo(end.plusSeconds(1800));
        assertThat(cancellationRepository.findById(cancellation.getId()).orElseThrow()
                .getReasonCode()).isEqualTo(CancellationReason.USER);
    }

    @Test
    void postgresRejectsOverlappingRoomOccupancy() {
        UUID roomId = UUID.randomUUID();
        UUID firstRequestId = insertBookingRequest();
        UUID secondRequestId = insertBookingRequest();
        insertReservation(firstRequestId, roomId);

        assertThatThrownBy(() -> insertReservation(secondRequestId, roomId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void occupancySnapshotIncludesBufferedIntervalsAndEveryRequestedRoom() {
        UUID occupiedRoomId = UUID.randomUUID();
        UUID emptyRoomId = UUID.randomUUID();
        UUID requestId = insertBookingRequest();
        insertReservation(requestId, occupiedRoomId);
        jdbcTemplate.update("""
                INSERT INTO reservation_occurrence
                    (id, reservation_id, room_id, start_at, end_at, occupied_period, occupancy_active)
                SELECT ?, id, ?, TIMESTAMPTZ '2026-11-07 10:00:00Z',
                       TIMESTAMPTZ '2026-11-07 11:00:00Z',
                       tstzrange(TIMESTAMPTZ '2026-11-07 10:00:00Z',
                                 TIMESTAMPTZ '2026-11-07 11:05:00Z', '[)'), TRUE
                FROM reservation WHERE booking_request_id = ?
                """, UUID.randomUUID(), occupiedRoomId, requestId);

        var snapshot = bookingOccupancyService.snapshot(new BookingOccupancyRequest(
                java.util.List.of(occupiedRoomId, emptyRoomId),
                Instant.parse("2026-11-07T10:30:00Z"),
                Instant.parse("2026-11-07T11:10:00Z"),
                "v1"));

        assertThat(snapshot.snapshotAt()).isNotNull();
        assertThat(snapshot.rooms()).extracting("roomId").containsExactly(occupiedRoomId, emptyRoomId);
        assertThat(snapshot.rooms().getFirst().intervals()).containsExactly(
                new rw.rra.roomiq.booking.domain.dto.BookingOccupancySnapshotResponse.OccupiedInterval(
                        Instant.parse("2026-11-07T10:00:00Z"), Instant.parse("2026-11-07T11:05:00Z")));
        assertThat(snapshot.rooms().get(1).intervals()).isEmpty();

        var adjacentSnapshot = bookingOccupancyService.snapshot(new BookingOccupancyRequest(
                java.util.List.of(occupiedRoomId),
                Instant.parse("2026-11-07T11:05:00Z"),
                Instant.parse("2026-11-07T12:00:00Z"),
                "v1"));
        assertThat(adjacentSnapshot.rooms().getFirst().intervals()).isEmpty();

        jdbcTemplate.update("UPDATE reservation_occurrence SET occupancy_active = FALSE WHERE room_id = ?",
                occupiedRoomId);
        var releasedSnapshot = bookingOccupancyService.snapshot(new BookingOccupancyRequest(
                java.util.List.of(occupiedRoomId),
                Instant.parse("2026-11-07T10:30:00Z"),
                Instant.parse("2026-11-07T11:10:00Z"),
                "v1"));
        assertThat(releasedSnapshot.rooms().getFirst().intervals()).isEmpty();
    }

    @Test
    void postgresRejectsInvalidBookingRequestIntervalsAndAttendeeCounts() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO booking_request
                    (id, request_reference, request_type, requested_by_user_id, department_id, room_id,
                     office_building_id, title, requested_start, requested_end, attendee_count, status)
                VALUES (?, ?, 'SECRETARY_REQUEST', ?, ?, ?, ?, 'Test', TIMESTAMPTZ '2026-10-07 12:00:00Z',
                        TIMESTAMPTZ '2026-10-07 11:00:00Z', 1, 'DRAFT')
                """, UUID.randomUUID(), "RRA-" + UUID.randomUUID(), userId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO booking_request
                    (id, request_reference, request_type, requested_by_user_id, department_id, room_id,
                     office_building_id, title, requested_start, requested_end, attendee_count, status)
                VALUES (?, ?, 'SECRETARY_REQUEST', ?, ?, ?, ?, 'Test', TIMESTAMPTZ '2026-10-07 10:00:00Z',
                        TIMESTAMPTZ '2026-10-07 11:00:00Z', 0, 'DRAFT')
                """, UUID.randomUUID(), "RRA-" + UUID.randomUUID(), userId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private Set<String> tableNames() {
        return new HashSet<>(jdbcTemplate.query(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                (resultSet, rowNumber) -> resultSet.getString(1)));
    }

    private Set<String> importedForeignKeyColumns(String tableName) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet keys = connection.getMetaData().getImportedKeys(connection.getCatalog(), "public", tableName)) {
            while (keys.next()) {
                columns.add(keys.getString("FKCOLUMN_NAME"));
            }
        }
        return columns;
    }

    private UUID insertBookingRequest() {
        UUID requestId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO booking_request
                    (id, request_reference, request_type, requested_by_user_id, department_id, room_id,
                     office_building_id, title, requested_start, requested_end, attendee_count, status)
                VALUES (?, ?, 'ADMIN_DIRECT_BOOKING', ?, ?, ?, ?, 'Overlap test',
                        TIMESTAMPTZ '2026-11-07 10:00:00Z', TIMESTAMPTZ '2026-11-07 11:00:00Z', 1, 'APPROVED')
                """, requestId, "RRA-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        return requestId;
    }

    private void insertReservation(UUID requestId, UUID roomId) {
        jdbcTemplate.update("""
                INSERT INTO reservation
                    (id, booking_request_id, room_id, organizer_user_id, occupied_period,
                     start_at, end_at, status)
                VALUES (?, ?, ?, ?, tstzrange(TIMESTAMPTZ '2026-11-07 10:00:00Z',
                        TIMESTAMPTZ '2026-11-07 11:05:00Z', '[)'),
                        TIMESTAMPTZ '2026-11-07 10:00:00Z', TIMESTAMPTZ '2026-11-07 11:00:00Z', 'CONFIRMED')
                """, UUID.randomUUID(), requestId, roomId, UUID.randomUUID());
    }
}
