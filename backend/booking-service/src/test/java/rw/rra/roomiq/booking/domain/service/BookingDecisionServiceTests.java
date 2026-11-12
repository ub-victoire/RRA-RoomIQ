package rw.rra.roomiq.booking.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import rw.rra.roomiq.booking.domain.dto.BookingDecisionRequest;
import rw.rra.roomiq.booking.domain.entity.ApprovalDecision;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;
import rw.rra.roomiq.booking.domain.entity.Meeting;
import rw.rra.roomiq.booking.domain.entity.Reservation;
import rw.rra.roomiq.booking.domain.enums.ApprovalDecisionType;
import rw.rra.roomiq.booking.domain.enums.BookingRequestStatus;
import rw.rra.roomiq.booking.domain.enums.BookingRequestType;
import rw.rra.roomiq.booking.domain.repository.ApprovalDecisionRepository;
import rw.rra.roomiq.booking.domain.repository.BookingRequestRepository;
import rw.rra.roomiq.booking.domain.repository.ReservationRepository;
import rw.rra.roomiq.booking.integration.BookingAuthorizationClient;
import rw.rra.roomiq.booking.integration.BookingAuthorizationResponse;
import rw.rra.roomiq.common.web.DomainException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingDecisionServiceTests {
    private static final String TOKEN = "******";
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    @Mock
    private BookingRequestRepository bookingRequests;
    @Mock
    private ApprovalDecisionRepository decisions;
    @Mock
    private ReservationRepository reservations;
    @Mock
    private BookingAuthorizationClient authorizationClient;
    @Mock
    private BookingConfirmationService confirmationService;
    @Mock
    private BookingDomainEventOutboxService domainEvents;
    @Mock
    private BookingSourceEvidenceOutboxService sourceEvidenceOutbox;

    private BookingDecisionService service;
    private UUID requestId;
    private UUID requesterId;
    private UUID approverId;
    private UUID buildingId;
    private BookingRequest pendingRequest;
    private BookingAuthorizationResponse authorization;

    @BeforeEach
    void setUp() {
        service = new BookingDecisionService(bookingRequests, decisions, reservations, authorizationClient,
                confirmationService, domainEvents, sourceEvidenceOutbox, Clock.fixed(NOW, ZoneOffset.UTC));
        requestId = UUID.randomUUID();
        requesterId = UUID.randomUUID();
        approverId = UUID.randomUUID();
        buildingId = UUID.randomUUID();
        pendingRequest = new BookingRequest("BR-" + UUID.randomUUID(), BookingRequestType.SECRETARY_REQUEST,
                requesterId, UUID.randomUUID(), UUID.randomUUID(), buildingId, null, "Planning session",
                "Review the roadmap", Instant.parse("2030-04-10T10:00:00Z"),
                Instant.parse("2030-04-10T11:00:00Z"), 5, false,
                BookingRequestStatus.PENDING_APPROVAL, "idem-" + UUID.randomUUID(), NOW.minusSeconds(60));
        authorization = new BookingAuthorizationResponse(approverId, requesterId, "Trusted Requester");
        when(bookingRequests.findByIdForUpdate(requestId)).thenReturn(Optional.of(pendingRequest));
        lenient().when(authorizationClient.authorizeApproval(requesterId, buildingId)).thenReturn(authorization);
        Reservation reservation = org.mockito.Mockito.mock(Reservation.class);
        Meeting meeting = org.mockito.Mockito.mock(Meeting.class);
        lenient().when(reservation.getId()).thenReturn(UUID.randomUUID());
        lenient().when(meeting.getId()).thenReturn(UUID.randomUUID());
        lenient().when(confirmationService.confirm(any(BookingRequest.class),
                any(BookingAuthorizationResponse.class), eq(NOW)))
                .thenAnswer(invocation -> {
                    BookingRequest request = invocation.getArgument(0);
                    request.approve();
                    return new BookingConfirmationService.ConfirmedBooking(reservation, meeting, 1);
                });
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("Authorization", TOKEN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));
    }

    @Test
    void approvalPersistsDecisionAndDelegatesAtomicConfirmation() {
        var response = service.decide(requestId, new BookingDecisionRequest(ApprovalDecisionType.APPROVED,
                "  Reviewed by scoped administrator  "));

        assertThat(response.requestStatus()).isEqualTo(BookingRequestStatus.APPROVED);
        assertThat(response.decision()).isEqualTo(ApprovalDecisionType.APPROVED);
        assertThat(response.decidedByUserId()).isEqualTo(approverId);
        assertThat(response.decidedAt()).isEqualTo(NOW);
        assertThat(response.comment()).isEqualTo("Reviewed by scoped administrator");
        assertThat(pendingRequest.getStatus()).isEqualTo(BookingRequestStatus.APPROVED);

        ArgumentCaptor<ApprovalDecision> decision = ArgumentCaptor.forClass(ApprovalDecision.class);
        verify(decisions).saveAndFlush(decision.capture());
        assertThat(decision.getValue().getDecision()).isEqualTo(ApprovalDecisionType.APPROVED);
        assertThat(decision.getValue().getDecidedByUserId()).isEqualTo(approverId);
        assertThat(decision.getValue().getDecidedAt()).isEqualTo(NOW);
        verify(confirmationService).confirm(pendingRequest, authorization, NOW);
    }

    @Test
    void rejectionPersistsDecisionWithoutCreatingReservation() {
        var response = service.decide(requestId,
                new BookingDecisionRequest(ApprovalDecisionType.REJECTED, "Policy denied"));

        assertThat(response.requestStatus()).isEqualTo(BookingRequestStatus.REJECTED);
        assertThat(response.decision()).isEqualTo(ApprovalDecisionType.REJECTED);
        assertThat(response.reservationId()).isNull();
        assertThat(response.meetingId()).isNull();
        assertThat(pendingRequest.getStatus()).isEqualTo(BookingRequestStatus.REJECTED);
        verify(decisions).saveAndFlush(any(ApprovalDecision.class));
        verify(bookingRequests).saveAndFlush(pendingRequest);
        verifyNoInteractions(confirmationService);
    }

    @Test
    void staleRequestCannotBeDecidedOrWriteHistory() {
        pendingRequest = new BookingRequest("BR-" + UUID.randomUUID(), BookingRequestType.SECRETARY_REQUEST,
                requesterId, UUID.randomUUID(), UUID.randomUUID(), buildingId, null, "Planning session", null,
                Instant.parse("2030-04-10T10:00:00Z"), Instant.parse("2030-04-10T11:00:00Z"),
                5, false, BookingRequestStatus.DRAFT, null, NOW.minusSeconds(60));
        when(bookingRequests.findByIdForUpdate(requestId)).thenReturn(Optional.of(pendingRequest));

        assertThatThrownBy(() -> service.decide(requestId,
                new BookingDecisionRequest(ApprovalDecisionType.APPROVED, null)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException domainException = (DomainException) error;
                    assertThat(domainException.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(domainException.code()).isEqualTo("BOOKING_REQUEST_STATE_CONFLICT");
                });

        verify(decisions, never()).saveAndFlush(any());
        verify(confirmationService, never()).confirm(any(), any(), any());
    }

    @Test
    void requesterCannotApproveTheirOwnRequest() {
        when(authorizationClient.authorizeApproval(requesterId, buildingId))
                .thenReturn(new BookingAuthorizationResponse(requesterId, requesterId, "Requester"));

        assertThatThrownBy(() -> service.decide(requestId,
                new BookingDecisionRequest(ApprovalDecisionType.APPROVED, null)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).status()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(decisions, never()).saveAndFlush(any());
        verifyNoInteractions(confirmationService);
    }

    @Test
    void identityAuthorizationFailurePreventsConfirmationAndWrites() {
        when(authorizationClient.authorizeApproval(requesterId, buildingId))
                .thenThrow(new DomainException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Denied"));

        assertThatThrownBy(() -> service.decide(requestId,
                new BookingDecisionRequest(ApprovalDecisionType.APPROVED, null)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).status()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(decisions, never()).saveAndFlush(any());
        verify(reservations, never()).existsByBookingRequest_Id(any());
        verifyNoInteractions(confirmationService);
        assertThat(pendingRequest.getStatus()).isEqualTo(BookingRequestStatus.PENDING_APPROVAL);
    }

    @Test
    void missingTrustedRequesterProfilePreventsConfirmationAndWrites() {
        when(authorizationClient.authorizeApproval(requesterId, buildingId))
                .thenReturn(new BookingAuthorizationResponse(approverId, requesterId, null));

        assertThatThrownBy(() -> service.decide(requestId,
                new BookingDecisionRequest(ApprovalDecisionType.APPROVED, null)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).status())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));

        verify(decisions, never()).saveAndFlush(any());
        verifyNoInteractions(confirmationService);
    }

    @Test
    void duplicateDecisionOrReservationPreventsConfirmation() {
        when(decisions.existsByBookingRequest_Id(requestId)).thenReturn(true);

        assertThatThrownBy(() -> service.decide(requestId,
                new BookingDecisionRequest(ApprovalDecisionType.APPROVED, null)))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException domainException = (DomainException) error;
                    assertThat(domainException.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(domainException.code()).isEqualTo("BOOKING_DECISION_CONFLICT");
                });

        verifyNoInteractions(confirmationService);
        verify(decisions, never()).saveAndFlush(any());
    }
}
