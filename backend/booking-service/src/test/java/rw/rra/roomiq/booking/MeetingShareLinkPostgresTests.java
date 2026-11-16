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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import rw.rra.roomiq.booking.domain.dto.CreateMeetingShareLinkRequest;
import rw.rra.roomiq.booking.domain.dto.PublicMeetingCardResponse;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;
import rw.rra.roomiq.booking.domain.entity.Meeting;
import rw.rra.roomiq.booking.domain.entity.MeetingShareLink;
import rw.rra.roomiq.booking.domain.entity.Reservation;
import rw.rra.roomiq.booking.domain.enums.BookingRequestStatus;
import rw.rra.roomiq.booking.domain.enums.BookingRequestType;
import rw.rra.roomiq.booking.domain.enums.MeetingVisibility;
import rw.rra.roomiq.booking.domain.enums.ReservationStatus;
import rw.rra.roomiq.booking.domain.repository.BookingRequestRepository;
import rw.rra.roomiq.booking.domain.repository.MeetingRepository;
import rw.rra.roomiq.booking.domain.repository.MeetingShareLinkRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationRepository;
import rw.rra.roomiq.booking.domain.service.MeetingShareLinkService;
import rw.rra.roomiq.booking.integration.BookingAuthorizationClient;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient;
import rw.rra.roomiq.booking.integration.MeetingAuthorizationRequest;
import rw.rra.roomiq.booking.integration.MeetingAuthorizationResponse;
import rw.rra.roomiq.common.web.DomainException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@Import(MeetingShareLinkPostgresTests.TestClients.class)
class MeetingShareLinkPostgresTests {
    private static final String TOKEN = "Bearer test-caller-token";
    private static final String TOKEN_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final Instant START = Instant.parse("2030-04-10T10:00:00Z");
    private static final Instant END = Instant.parse("2030-04-10T11:00:00Z");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("roomiq.booking.event-relay.enabled", () -> "false");
        registry.add("roomiq.booking.share-token.key-ring", () -> "test=" + TOKEN_KEY);
        registry.add("roomiq.booking.share-token.active-key-version", () -> "test");
    }

    @Autowired
    private MeetingShareLinkService service;
    @Autowired
    private BookingRequestRepository bookingRequests;
    @Autowired
    private ReservationRepository reservations;
    @Autowired
    private MeetingRepository meetings;
    @Autowired
    private MeetingShareLinkRepository links;
    @Autowired
    private BookingAuthorizationClient authorization;
    @Autowired
    private BookingOwnerServicesClient ownerServices;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        reset(authorization, ownerServices);
        for (String table : List.of("meeting_share_link", "meeting_participant", "meeting",
                "reservation_occurrence", "booking_extension", "cancellation", "approval_decision",
                "booking_source_evidence_outbox", "reservation", "booking_request")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", TOKEN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void createsHashOnlyLinkAndReturnsOnlyTheAllowlistedTimezoneBoundCard() throws Exception {
        Meeting meeting = approvedMeeting();
        UUID actorId = UUID.randomUUID();
        authorizeManagement(actorId);
        when(ownerServices.authoritativeBuildingTimezone(meeting.getReservation().getBookingRequest()
                .getOfficeBuildingId(), TOKEN)).thenReturn("Africa/Kigali");
        Instant expiry = Instant.now().plusSeconds(3600);

        var created = service.create(meeting.getId(), new CreateMeetingShareLinkRequest(expiry), "share-create-1");

        assertThat(created.token()).matches("v1\\.test\\.[A-Za-z0-9_-]{43}");
        assertThat(created.replayed()).isFalse();
        assertThat(created.shareLinkId()).isNotNull();
        assertThat(created.expiresAt()).isEqualTo(expiry.truncatedTo(ChronoUnit.MICROS));
        MeetingShareLink persisted = links.findById(created.shareLinkId()).orElseThrow();
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(created.token().getBytes(StandardCharsets.US_ASCII)));
        assertThat(persisted.getTokenHash()).isEqualTo(expectedHash).isNotEqualTo(created.token());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT token_hash FROM meeting_share_link WHERE id = ?",
                String.class, created.shareLinkId())).isEqualTo(expectedHash);
        assertThat(persisted.getCreatedByUserId()).isEqualTo(actorId);
        assertThat(persisted.getTimezone()).isEqualTo("Africa/Kigali");
        verify(ownerServices).authoritativeBuildingTimezone(
                meeting.getReservation().getBookingRequest().getOfficeBuildingId(), TOKEN);
        verify(authorization).authorizeMeeting(any(MeetingAuthorizationRequest.class));

        when(ownerServices.authoritativeBuildingTimezone(any(UUID.class), eq(TOKEN)))
                .thenReturn("America/New_York");
        PublicMeetingCardResponse card = service.readPublicCard(created.token());

        assertThat(card.title()).isEqualTo(meeting.getTitle());
        assertThat(card.organizerDisplayName()).isEqualTo("Trusted organizer");
        assertThat(card.timezone()).isEqualTo("Africa/Kigali");
        assertThat(card.startAt().toInstant()).isEqualTo(START);
        assertThat(card.endAt().toInstant()).isEqualTo(END);
        assertThat(card.startAt().getOffset()).isEqualTo(START.atZone(ZoneId.of("Africa/Kigali")).getOffset());
        assertThat(List.of(PublicMeetingCardResponse.class.getRecordComponents()))
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("title", "startAt", "endAt", "timezone", "organizerDisplayName");
        assertThat(links.findById(created.shareLinkId()).orElseThrow().getViewCount()).isEqualTo(1);
    }

    @Test
    void equivalentShareLinkRetryReturnsSameTokenAndKeyReuseWithDifferentPayloadConflicts() throws SQLException {
        Meeting meeting = approvedMeeting();
        UUID actorId = UUID.randomUUID();
        authorizeManagement(actorId);
        when(ownerServices.authoritativeBuildingTimezone(any(UUID.class), eq(TOKEN)))
                .thenReturn("Africa/Kigali");
        Instant expiry = Instant.now().plusSeconds(3600);

        var first = service.create(meeting.getId(), new CreateMeetingShareLinkRequest(expiry), "same-share-key");
        var replay = service.create(meeting.getId(), new CreateMeetingShareLinkRequest(expiry), "same-share-key");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.shareLinkId()).isEqualTo(first.shareLinkId());
        assertThat(replay.token()).isEqualTo(first.token());
        assertThat(links.count()).isEqualTo(1);
        verify(ownerServices, times(1)).authoritativeBuildingTimezone(any(UUID.class), eq(TOKEN));

        assertThatThrownBy(() -> service.create(meeting.getId(),
                new CreateMeetingShareLinkRequest(expiry.plusSeconds(1)), "same-share-key"))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException exception = (DomainException) error;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
                });
        assertThat(links.count()).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyShareLinkCreatesReturnOnePersistedLinkAndReplay() throws Exception {
        Meeting meeting = approvedMeeting();
        UUID actorId = UUID.randomUUID();
        authorizeManagement(actorId);
        when(ownerServices.authoritativeBuildingTimezone(any(UUID.class), eq(TOKEN)))
                .thenReturn("Africa/Kigali");

        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> concurrentCreate(meeting.getId(), ready, start));
            var second = executor.submit(() -> concurrentCreate(meeting.getId(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var firstResult = first.get(20, TimeUnit.SECONDS);
            var secondResult = second.get(20, TimeUnit.SECONDS);

            assertThat(firstResult.shareLinkId()).isEqualTo(secondResult.shareLinkId());
            assertThat(firstResult.token()).isEqualTo(secondResult.token());
            assertThat(List.of(firstResult.replayed(), secondResult.replayed()))
                    .containsExactlyInAnyOrder(false, true);
        }
        assertThat(links.count()).isEqualTo(1);
    }

    @Test
    void replayFailsClosedWhenPersistedHashCannotBeReproduced() throws SQLException {
        Meeting meeting = approvedMeeting();
        authorizeManagement(UUID.randomUUID());
        when(ownerServices.authoritativeBuildingTimezone(any(UUID.class), eq(TOKEN)))
                .thenReturn("Africa/Kigali");
        var created = service.create(meeting.getId(), new CreateMeetingShareLinkRequest(null), "hash-mismatch");
        jdbcTemplate.update("UPDATE meeting_share_link SET token_hash = ? WHERE id = ?",
                "0".repeat(64), created.shareLinkId());

        assertThatThrownBy(() -> service.create(meeting.getId(),
                new CreateMeetingShareLinkRequest(null), "hash-mismatch"))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException exception = (DomainException) error;
                    assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.code()).isEqualTo("SHARE_LINK_TOKEN_REPLAY_UNAVAILABLE");
                });
        assertThat(links.count()).isEqualTo(1);
    }

    @Test
    void refusesUnapprovedMeetingAndAuthorizationDenialBeforeTimezoneLookupOrWrite() throws Exception {
        BookingRequest request = bookingRequests.saveAndFlush(bookingRequest(
                UUID.randomUUID(), BookingRequestStatus.PENDING_APPROVAL));
        Reservation reservation = saveReservation(request);
        Meeting meeting = meetings.saveAndFlush(new Meeting(reservation, "Pending meeting", null,
                "Trusted organizer", null, null, MeetingVisibility.PRIVATE, Instant.now()));
        authorizeManagement(UUID.randomUUID());

        assertThatThrownBy(() -> service.create(meeting.getId(),
                new CreateMeetingShareLinkRequest(null), "ineligible"))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException exception = (DomainException) error;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("SHARE_LINK_MEETING_NOT_ELIGIBLE");
                });
        assertThat(links.count()).isZero();
        verifyNoInteractions(ownerServices);

        Meeting approvedMeeting = approvedMeeting();
        reset(authorization, ownerServices);
        when(authorization.authorizeMeeting(any(MeetingAuthorizationRequest.class)))
                .thenThrow(new DomainException(HttpStatus.FORBIDDEN, "MEETING_ACCESS_DENIED", "Denied"));

        assertThatThrownBy(() -> service.create(approvedMeeting.getId(),
                new CreateMeetingShareLinkRequest(null), "denied"))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).code()).isEqualTo("MEETING_ACCESS_DENIED"));
        assertThat(links.count()).isZero();
        verifyNoInteractions(ownerServices);
    }

    @Test
    void invalidExpiredAndRevokedTokensReturnTheSameNotFoundAndRevocationIsPersisted() throws Exception {
        Meeting meeting = approvedMeeting();
        UUID actorId = UUID.randomUUID();
        authorizeManagement(actorId);
        when(ownerServices.authoritativeBuildingTimezone(any(UUID.class), eq(TOKEN)))
                .thenReturn("Africa/Kigali");
        var created = service.create(meeting.getId(), new CreateMeetingShareLinkRequest(null), "revoke-test");

        assertPublicNotFound("not-a-token");

        var revoked = service.revoke(created.shareLinkId());
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(links.findById(created.shareLinkId()).orElseThrow().getRevokedAt()).isEqualTo(revoked.revokedAt());
        assertPublicNotFound(created.token());
        var repeatedRevoke = service.revoke(created.shareLinkId());
        assertThat(repeatedRevoke.revokedAt()).isEqualTo(revoked.revokedAt());
        assertThat(links.findById(created.shareLinkId()).orElseThrow().getViewCount()).isZero();
        var replayed = service.create(meeting.getId(), new CreateMeetingShareLinkRequest(null), "revoke-test");
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.token()).isEqualTo(created.token());
        assertPublicNotFound(replayed.token());

        Meeting secondMeeting = approvedMeeting();
        String expiredToken = "v1.test." + "A".repeat(43);
        MeetingShareLink expired = links.saveAndFlush(new MeetingShareLink(secondMeeting, hashToken(expiredToken),
                "Africa/Kigali", actorId, null, Instant.now().minusSeconds(1), null, 0));
        assertPublicNotFound(expiredToken);
        assertThat(expired.getViewCount()).isZero();
    }

    @Test
    void concurrentPublicReadsSerializeViewCountUpdates() throws Exception {
        Meeting meeting = approvedMeeting();
        authorizeManagement(UUID.randomUUID());
        when(ownerServices.authoritativeBuildingTimezone(any(UUID.class), eq(TOKEN)))
                .thenReturn("Africa/Kigali");
        var created = service.create(meeting.getId(), new CreateMeetingShareLinkRequest(null), "concurrent-create");

        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> concurrentRead(created.token(), ready, start));
            var second = executor.submit(() -> concurrentRead(created.token(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).startAt().toInstant()).isEqualTo(START);
            assertThat(second.get(20, TimeUnit.SECONDS).startAt().toInstant()).isEqualTo(START);
        }
        assertThat(links.findById(created.shareLinkId()).orElseThrow().getViewCount()).isEqualTo(2);
    }

    private PublicMeetingCardResponse concurrentRead(String token, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent share-link test did not start");
        }
        return service.readPublicCard(token);
    }

    private rw.rra.roomiq.booking.domain.dto.MeetingShareLinkCreatedResponse concurrentCreate(
            UUID meetingId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", TOKEN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent share-link create test did not start");
            }
            return service.create(meetingId, new CreateMeetingShareLinkRequest(null), "concurrent-same-key");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private void assertPublicNotFound(String token) {
        assertThatThrownBy(() -> service.readPublicCard(token))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException exception = (DomainException) error;
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo("PUBLIC_MEETING_NOT_FOUND");
                });
    }

    private Meeting approvedMeeting() throws SQLException {
        UUID organizerId = UUID.randomUUID();
        BookingRequest request = bookingRequests.saveAndFlush(bookingRequest(
                organizerId, BookingRequestStatus.APPROVED));
        Reservation reservation = saveReservation(request);
        return meetings.saveAndFlush(new Meeting(reservation, "Planning session", "Private agenda",
                "Trusted organizer", "Private instructions", "private-contact@example.test",
                MeetingVisibility.PRIVATE, Instant.now()));
    }

    private BookingRequest bookingRequest(UUID organizerId, BookingRequestStatus status) {
        return new BookingRequest("SHARE-" + UUID.randomUUID(), BookingRequestType.SECRETARY_REQUEST,
                organizerId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                "Planning session", "Private agenda", START, END, 4, false, status, null, Instant.now());
    }

    private Reservation saveReservation(BookingRequest request) throws SQLException {
        var occupied = new org.postgresql.util.PGobject();
        occupied.setType("tstzrange");
        occupied.setValue("[" + START + "," + END.plusSeconds(300) + ")");
        return reservations.saveAndFlush(new Reservation(request, request.getRoomId(),
                request.getRequestedByUserId(), null, occupied, START, END, 5, ReservationStatus.CONFIRMED,
                Instant.now()));
    }

    private void authorizeManagement(UUID actorId) {
        when(authorization.currentBearerToken()).thenReturn(TOKEN);
        when(authorization.authorizeMeeting(any(MeetingAuthorizationRequest.class))).thenAnswer(invocation -> {
            MeetingAuthorizationRequest request = invocation.getArgument(0);
            return new MeetingAuthorizationResponse(actorId, request.buildingId(), request.departmentId(),
                    request.organizerUserId(), request.visibility(), "Trusted organizer", null, null);
        });
    }

    private static String hashToken(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
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
