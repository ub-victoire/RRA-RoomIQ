package rw.rra.roomiq.booking.domain.service;

import org.junit.jupiter.api.AfterEach;
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
import rw.rra.roomiq.booking.domain.dto.CreateBookingRequest;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;
import rw.rra.roomiq.booking.domain.enums.BookingRequestStatus;
import rw.rra.roomiq.booking.domain.enums.BookingRequestType;
import rw.rra.roomiq.booking.domain.repository.BookingRequestRepository;
import rw.rra.roomiq.booking.integration.BookingAuthorizationClient;
import rw.rra.roomiq.booking.integration.BookingAuthorizationRequest;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient;
import rw.rra.roomiq.booking.integration.BookingOwnerServicesClient.SchedulingOccurrence;
import rw.rra.roomiq.common.web.DomainException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingRequestServiceTests {
    private static final String TOKEN = "Bearer caller-token";
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    @Mock
    private BookingRequestRepository repository;
    @Mock
    private BookingAuthorizationClient authorizationClient;
    @Mock
    private BookingOwnerServicesClient ownerServicesClient;
    @Mock
    private PostgresIdempotencyLock idempotencyLock;
    @Mock
    private BookingDomainEventOutboxService domainEvents;
    @Mock
    private BookingSourceEvidenceOutboxService sourceEvidenceOutbox;

    private BookingRequestService service;
    private UUID actorId;
    private UUID buildingId;
    private UUID departmentId;
    private UUID roomId;
    private BookingOwnerServicesClient.ValidatedBookingReferences references;

    @BeforeEach
    void setUp() {
        service = new BookingRequestService(repository, authorizationClient, ownerServicesClient, idempotencyLock,
                domainEvents, sourceEvidenceOutbox, Clock.fixed(NOW, ZoneOffset.UTC));
        actorId = UUID.randomUUID();
        buildingId = UUID.randomUUID();
        departmentId = UUID.randomUUID();
        roomId = UUID.randomUUID();
        references = new BookingOwnerServicesClient.ValidatedBookingReferences(
                buildingId, departmentId, roomId, false, "Africa/Kigali", false, 5,
                List.of(new SchedulingOccurrence(
                        request().requestedStart().atZone(ZoneId.of("Africa/Kigali")).toLocalDate(),
                        request().requestedStart(), request().requestedEnd())));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", TOKEN);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void createUsesCurrentIdentityAndOwnerFactsAndPersistsAValidatedDraft() {
        when(authorizationClient.authorizeCurrentCaller(any())).thenReturn(actorId);
        when(ownerServicesClient.validateRequest(any(), any(), any())).thenReturn(references);
        when(repository.findByIdempotencyKey("request-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(BookingRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(request(), "request-1");

        ArgumentCaptor<BookingRequest> persisted = ArgumentCaptor.forClass(BookingRequest.class);
        verify(repository).saveAndFlush(persisted.capture());
        BookingRequest entity = persisted.getValue();
        assertThat(entity.getRequestedByUserId()).isEqualTo(actorId);
        assertThat(entity.getRequestType()).isEqualTo(BookingRequestType.SECRETARY_REQUEST);
        assertThat(entity.getStatus()).isEqualTo(BookingRequestStatus.DRAFT);
        assertThat(entity.getOfficeBuildingId()).isEqualTo(buildingId);
        assertThat(entity.getDepartmentId()).isEqualTo(departmentId);
        assertThat(entity.getRoomId()).isEqualTo(roomId);
        assertThat(entity.getIdempotencyKey()).isEqualTo("request-1");
        assertThat(entity.getTitle()).isEqualTo("Planning meeting");
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(response.requestedByUserId()).isEqualTo(actorId);
        assertThat(response.status()).isEqualTo(BookingRequestStatus.DRAFT);
        verify(ownerServicesClient).validateRequest(any(), org.mockito.ArgumentMatchers.eq(TOKEN),
                org.mockito.ArgumentMatchers.eq(NOW));
    }

    @Test
    void submitRevalidatesOwnerDataAndTransitionsOnlyTheRequestersDraft() {
        when(authorizationClient.authorizeCurrentCaller(any())).thenReturn(actorId);
        when(repository.findByIdForUpdate(any())).thenReturn(Optional.of(draft()));
        when(ownerServicesClient.validateRequest(any(), any(), any())).thenReturn(references);
        when(repository.saveAndFlush(any(BookingRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.submit(UUID.randomUUID());

        assertThat(response.status()).isEqualTo(BookingRequestStatus.PENDING_APPROVAL);
        ArgumentCaptor<BookingRequest> persisted = ArgumentCaptor.forClass(BookingRequest.class);
        verify(repository).saveAndFlush(persisted.capture());
        assertThat(persisted.getValue().getStatus()).isEqualTo(BookingRequestStatus.PENDING_APPROVAL);
        ArgumentCaptor<BookingAuthorizationRequest> authorization =
                ArgumentCaptor.forClass(BookingAuthorizationRequest.class);
        org.mockito.Mockito.verify(authorizationClient, org.mockito.Mockito.times(3))
                .authorizeCurrentCaller(authorization.capture());
        assertThat(authorization.getAllValues().getLast().action())
                .isEqualTo(BookingAuthorizationRequest.Action.REQUEST_SUBMIT);
        assertThat(authorization.getAllValues().getLast().resourceOwnerUserId()).isEqualTo(actorId);
        verify(ownerServicesClient).validateRequest(any(), org.mockito.ArgumentMatchers.eq(TOKEN),
                org.mockito.ArgumentMatchers.eq(NOW));
    }

    @Test
    void nonDraftCannotBeSubmittedOrWrittenAgain() {
        BookingRequest alreadySubmitted = new BookingRequest("BR-pending", BookingRequestType.SECRETARY_REQUEST,
                actorId, departmentId, roomId, buildingId, null, "Planning meeting", null,
                request().requestedStart(), request().requestedEnd(), 6, false,
                BookingRequestStatus.PENDING_APPROVAL, null, NOW);
        when(authorizationClient.authorizeCurrentCaller(any())).thenReturn(actorId);
        when(repository.findByIdForUpdate(any())).thenReturn(Optional.of(alreadySubmitted));

        assertThatThrownBy(() -> service.submit(UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).status()).isEqualTo(HttpStatus.CONFLICT));

        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(ownerServicesClient);
    }

    @Test
    void reviewerCannotSubmitAnotherRequestersDraftOrTriggerOwnerValidation() {
        UUID reviewerId = UUID.randomUUID();
        when(authorizationClient.authorizeCurrentCaller(any()))
                .thenReturn(reviewerId)
                .thenThrow(new DomainException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Denied"));
        when(repository.findByIdForUpdate(any())).thenReturn(Optional.of(draft()));

        assertThatThrownBy(() -> service.submit(UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .hasMessage("Denied");

        verifyNoInteractions(ownerServicesClient);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void ownerServiceFailurePreventsBookingPersistence() {
        when(authorizationClient.authorizeCurrentCaller(any())).thenReturn(actorId);
        when(ownerServicesClient.validateRequest(any(), any(), any()))
                .thenThrow(new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "OWNER_UNAVAILABLE",
                        "Owner service unavailable"));

        assertThatThrownBy(() -> service.create(request(), null))
                .isInstanceOf(DomainException.class)
                .hasMessage("Owner service unavailable");

        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).findByIdempotencyKey(any());
    }

    @Test
    void identityDenialAfterOwnerValidationPreventsBookingPersistence() {
        when(authorizationClient.authorizeCurrentCaller(any()))
                .thenReturn(actorId)
                .thenThrow(new DomainException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Denied"));
        when(ownerServicesClient.validateRequest(any(), any(), any())).thenReturn(references);

        assertThatThrownBy(() -> service.create(request(), null))
                .isInstanceOf(DomainException.class)
                .hasMessage("Denied");

        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(repository);
    }

    @Test
    void exactIdempotentReplayReturnsTheExistingRequestWithoutWritingAgain() {
        when(authorizationClient.authorizeCurrentCaller(any())).thenReturn(actorId);
        when(ownerServicesClient.validateRequest(any(), any(), any())).thenReturn(references);
        BookingRequest prior = new BookingRequest("BR-existing", BookingRequestType.SECRETARY_REQUEST, actorId,
                departmentId, roomId, buildingId, null, "Planning meeting", "Quarterly plan",
                request().requestedStart(), request().requestedEnd(), 6, false,
                BookingRequestStatus.PENDING_APPROVAL, "request-1", NOW);
        when(repository.findByIdempotencyKey("request-1")).thenReturn(Optional.of(prior));

        var response = service.create(request(), "request-1");

        assertThat(response.requestReference()).isEqualTo("BR-existing");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void reusingIdempotencyKeyForDifferentDataReturnsConflictWithoutWriting() {
        when(authorizationClient.authorizeCurrentCaller(any())).thenReturn(actorId);
        when(ownerServicesClient.validateRequest(any(), any(), any())).thenReturn(references);
        BookingRequest prior = new BookingRequest("BR-existing", BookingRequestType.SECRETARY_REQUEST, actorId,
                departmentId, roomId, buildingId, null, "Different meeting", "Quarterly plan",
                request().requestedStart(), request().requestedEnd(), 6, false,
                BookingRequestStatus.PENDING_APPROVAL, "request-1", NOW);
        when(repository.findByIdempotencyKey("request-1")).thenReturn(Optional.of(prior));

        assertThatThrownBy(() -> service.create(request(), "request-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(error -> assertThat(((DomainException) error).status()).isEqualTo(HttpStatus.CONFLICT));

        verify(repository, never()).saveAndFlush(any());
    }

    private CreateBookingRequest request() {
        return new CreateBookingRequest(departmentId, roomId, buildingId, null, " Planning meeting ",
                " Quarterly plan ", Instant.parse("2026-10-10T10:00:00Z"),
                Instant.parse("2026-10-10T11:00:00Z"), 6, false);
    }

    private BookingRequest draft() {
        return new BookingRequest("BR-draft", BookingRequestType.SECRETARY_REQUEST, actorId,
                departmentId, roomId, buildingId, null, "Planning meeting", "Quarterly plan",
                request().requestedStart(), request().requestedEnd(), 6, false,
                BookingRequestStatus.DRAFT, null, NOW);
    }
}
