package rw.rra.roomiq.booking.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import rw.rra.roomiq.booking.domain.dto.CreateBookingRequest;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;
import rw.rra.roomiq.booking.domain.entity.Meeting;
import rw.rra.roomiq.booking.domain.entity.Reservation;
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
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient.SchedulingOccurrence;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient.ValidatedBookingReferences;
import rw.rra.roomiq.common.web.DomainException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookingDirectBookingServiceTests {
    private static final String TOKEN = "Bearer direct-booking-test";
    private static final Instant NOW = Instant.parse("2030-03-01T00:00:00Z");
    private static final Instant START = Instant.parse("2030-04-10T10:00:00Z");
    private static final Instant END = Instant.parse("2030-04-10T11:00:00Z");

    private final BookingRequestRepository requests = mock(BookingRequestRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final ReservationOccurrenceRepository occurrences = mock(ReservationOccurrenceRepository.class);
    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final BookingAuthorizationClient authorizationClient = mock(BookingAuthorizationClient.class);
    private final BookingOwnerServicesClient ownerServicesClient = mock(BookingOwnerServicesClient.class);
    private final BookingConfirmationService confirmationService = mock(BookingConfirmationService.class);
    private final PostgresIdempotencyLock idempotencyLock = mock(PostgresIdempotencyLock.class);
    private final BookingDomainEventOutboxService domainEvents = mock(BookingDomainEventOutboxService.class);
    private final BookingSourceEvidenceOutboxService sourceEvidenceOutbox =
            mock(BookingSourceEvidenceOutboxService.class);

    private final UUID actorId = UUID.randomUUID();
    private final UUID buildingId = UUID.randomUUID();
    private final UUID departmentId = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();
    private final CreateBookingRequest request = new CreateBookingRequest(departmentId, roomId, buildingId,
            null, "  Planning session  ", "  Agenda  ", START, END, 4, false);

    private BookingDirectBookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingDirectBookingService(requests, reservations, occurrences, meetings,
                authorizationClient, ownerServicesClient, confirmationService, idempotencyLock,
                domainEvents, sourceEvidenceOutbox, Clock.fixed(NOW, ZoneOffset.UTC));
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("Authorization", TOKEN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        when(authorizationClient.authorizeCurrentCaller(any(BookingAuthorizationRequest.class))).thenReturn(actorId);
        when(authorizationClient.authorizeDirectBooking(buildingId, departmentId, false))
                .thenReturn(authorization(DirectBookingAuthority.ADMIN));
        when(ownerServicesClient.validateRequest(any(BookingRequestFacts.class), eq(TOKEN), eq(NOW)))
                .thenAnswer(invocation -> references(invocation.getArgument(0), false, false));
        when(requests.saveAndFlush(any(BookingRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void adminMustSubmitPendingApprovalWhenActiveRoomRuleRequiresIt() {
        when(ownerServicesClient.validateRequest(any(BookingRequestFacts.class), eq(TOKEN), eq(NOW)))
                .thenAnswer(invocation -> references(invocation.getArgument(0), true, false));

        var response = service.create(request, null);

        BookingRequest saved = captureRequest();
        assertThat(response.status()).isEqualTo(BookingRequestStatus.PENDING_APPROVAL);
        assertThat(response.reservationId()).isNull();
        assertThat(response.meetingId()).isNull();
        assertThat(saved.getStatus()).isEqualTo(BookingRequestStatus.PENDING_APPROVAL);
        assertThat(saved.getRequestType()).isEqualTo(BookingRequestType.ADMIN_DIRECT_BOOKING);
        assertThat(saved.getRequestedByUserId()).isEqualTo(actorId);
        verify(domainEvents).record(eq(2), eq("BookingRequested"), eq("BOOKING_REQUEST"), isNull(),
                anyLong(), eq(NOW), anyList(), argThat(facts -> "NONE".equals(facts.get("notificationRoute"))));
        verify(confirmationService, never()).confirm(any(), any(), any());
    }

    @Test
    void eligibleSecretaryMustSubmitPendingApprovalWhenActiveRoomRuleRequiresIt() {
        when(authorizationClient.authorizeDirectBooking(buildingId, departmentId, false))
                .thenReturn(authorization(DirectBookingAuthority.SECRETARY));
        when(ownerServicesClient.validateRequest(any(BookingRequestFacts.class), eq(TOKEN), eq(NOW)))
                .thenAnswer(invocation -> references(invocation.getArgument(0), true, false));

        var response = service.create(request, null);

        assertThat(response.status()).isEqualTo(BookingRequestStatus.PENDING_APPROVAL);
        assertThat(response.reservationId()).isNull();
        assertThat(response.meetingId()).isNull();
        assertThat(captureRequest().getRequestType()).isEqualTo(BookingRequestType.SECRETARY_REQUEST);
        verify(confirmationService, never()).confirm(any(), any(), any());
    }

    @Test
    void adminCanConfirmImmediatelyOnlyWhenActiveRoomRuleDoesNotRequireApproval() {
        Reservation reservation = mock(Reservation.class);
        Meeting meeting = mock(Meeting.class);
        UUID reservationId = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        when(reservation.getId()).thenReturn(reservationId);
        when(meeting.getId()).thenReturn(meetingId);
        when(confirmationService.confirm(any(BookingRequest.class), any(BookingAuthorizationResponse.class), eq(NOW)))
                .thenAnswer(invocation -> {
                    BookingRequest pending = invocation.getArgument(0);
                    assertThat(pending.getStatus()).isEqualTo(BookingRequestStatus.PENDING_APPROVAL);
                    pending.approve();
                    return new BookingConfirmationService.ConfirmedBooking(reservation, meeting, 1);
                });

        var response = service.create(request, null);

        assertThat(response.status()).isEqualTo(BookingRequestStatus.APPROVED);
        assertThat(response.reservationId()).isEqualTo(reservationId);
        assertThat(response.meetingId()).isEqualTo(meetingId);
        verify(confirmationService).confirm(any(BookingRequest.class),
                any(BookingAuthorizationResponse.class), eq(NOW));
    }

    @Test
    void rejectsAuthorizationActorMismatchBeforeOwnerReadsOrWrites() {
        when(authorizationClient.authorizeDirectBooking(buildingId, departmentId, false))
                .thenReturn(new BookingAuthorizationResponse(UUID.randomUUID(), UUID.randomUUID(),
                        "Different actor", DirectBookingAuthority.ADMIN));

        assertThatThrownBy(() -> service.create(request, null))
                .isInstanceOf(DomainException.class)
                .satisfies(exception -> {
                    DomainException domainException = (DomainException) exception;
                    assertThat(domainException.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(domainException.code()).isEqualTo("IDENTITY_AUTHORIZATION_INCONSISTENT");
                });

        verifyNoInteractions(ownerServicesClient, requests, confirmationService);
    }

    @Test
    void vipSecretaryMustPassIdentityPrivilegeCheckBeforeRequestIsPersisted() {
        when(authorizationClient.authorizeDirectBooking(buildingId, departmentId, true))
                .thenThrow(new DomainException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "VIP permission missing"));
        when(authorizationClient.authorizeDirectBooking(buildingId, departmentId, false))
                .thenReturn(authorization(DirectBookingAuthority.SECRETARY));
        when(ownerServicesClient.validateRequest(any(BookingRequestFacts.class), eq(TOKEN), eq(NOW)))
                .thenAnswer(invocation -> references(invocation.getArgument(0), true, true));

        assertThatThrownBy(() -> service.create(request, null))
                .isInstanceOf(DomainException.class);

        verify(requests, never()).saveAndFlush(any());
        verify(confirmationService, never()).confirm(any(), any(), any());
    }

    private BookingRequest captureRequest() {
        var captor = org.mockito.ArgumentCaptor.forClass(BookingRequest.class);
        verify(requests).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private BookingAuthorizationResponse authorization(DirectBookingAuthority authority) {
        return new BookingAuthorizationResponse(actorId, actorId, "Trusted Identity Name", authority);
    }

    private ValidatedBookingReferences references(BookingRequestFacts facts, boolean approvalRequired,
                                                   boolean vipRoom) {
        LocalDate date = facts.startsAt().atZone(ZoneId.of("Africa/Kigali")).toLocalDate();
        return new ValidatedBookingReferences(buildingId, departmentId, roomId, vipRoom, "Africa/Kigali",
                approvalRequired, 5, List.of(new SchedulingOccurrence(date, facts.startsAt(), facts.endsAt())));
    }
}
