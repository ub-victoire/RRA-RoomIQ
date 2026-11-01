package rw.rra.roomiq.booking.domain.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import rw.rra.roomiq.booking.domain.dto.BookingRequestPageQuery;
import rw.rra.roomiq.booking.domain.dto.BookingRequestPageResponse;
import rw.rra.roomiq.booking.domain.dto.BookingRequestResponse;
import rw.rra.roomiq.booking.domain.dto.CreateBookingRequest;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;
import rw.rra.roomiq.booking.domain.enums.BookingRequestStatus;
import rw.rra.roomiq.booking.domain.enums.BookingRequestType;
import rw.rra.roomiq.booking.domain.repository.BookingRequestRepository;
import rw.rra.roomiq.booking.integration.BookingAuthorizationClient;
import rw.rra.roomiq.booking.integration.BookingAuthorizationRequest;
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
import java.util.regex.Pattern;

import static rw.rra.roomiq.booking.integration.BookingAuthorizationRequest.Action.AUTHENTICATE;
import static rw.rra.roomiq.booking.integration.BookingAuthorizationRequest.Action.REQUEST_CREATE;
import static rw.rra.roomiq.booking.integration.BookingAuthorizationRequest.Action.REQUEST_LIST;
import static rw.rra.roomiq.booking.integration.BookingAuthorizationRequest.Action.REQUEST_READ;
import static rw.rra.roomiq.booking.integration.BookingAuthorizationRequest.Action.REQUEST_SUBMIT;

@Service
public class BookingRequestService {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final BookingRequestRepository repository;
    private final BookingAuthorizationClient authorizationClient;
    private final BookingOwnerServicesClient ownerServicesClient;
    private final PostgresIdempotencyLock idempotencyLock;
    private final BookingDomainEventOutboxService domainEvents;
    private final Clock clock;

    @Autowired
    public BookingRequestService(BookingRequestRepository repository,
                                 BookingAuthorizationClient authorizationClient,
                                 BookingOwnerServicesClient ownerServicesClient,
                                 PostgresIdempotencyLock idempotencyLock,
                                 BookingDomainEventOutboxService domainEvents) {
        this(repository, authorizationClient, ownerServicesClient, idempotencyLock, domainEvents, Clock.systemUTC());
    }

    BookingRequestService(BookingRequestRepository repository,
                          BookingAuthorizationClient authorizationClient,
                          BookingOwnerServicesClient ownerServicesClient,
                          PostgresIdempotencyLock idempotencyLock,
                          BookingDomainEventOutboxService domainEvents,
                          Clock clock) {
        this.repository = repository;
        this.authorizationClient = authorizationClient;
        this.ownerServicesClient = ownerServicesClient;
        this.idempotencyLock = idempotencyLock;
        this.domainEvents = domainEvents;
        this.clock = clock;
    }

    @Transactional
    public BookingRequestResponse create(CreateBookingRequest request, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        UUID authenticatedActor = authorizationClient.authorizeCurrentCaller(
                new BookingAuthorizationRequest(AUTHENTICATE, null, null, null, null));
        Instant now = clock.instant();
        ValidatedBookingReferences references = ownerServicesClient.validateRequest(
                new BookingRequestFacts(request.departmentId(), request.roomId(), request.officeBuildingId(),
                        request.recurrenceRuleId(), request.requestedStart(), request.requestedEnd(),
                        request.attendeeCount(), request.externalGuests()),
                currentBearerToken(), now);
        UUID actor = authorizationClient.authorizeCurrentCaller(
                new BookingAuthorizationRequest(REQUEST_CREATE, references.officeBuildingId(),
                        references.departmentId(), null, references.vipRoom()));
        if (!authenticatedActor.equals(actor)) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_AUTHORIZATION_INCONSISTENT",
                    "Identity returned inconsistent actors during booking authorization");
        }

        if (idempotencyKey != null) {
            idempotencyLock.acquire("booking-request:create", idempotencyKey);
            Optional<BookingRequest> existing = repository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                BookingRequest prior = existing.get();
                if (actor.equals(prior.getRequestedByUserId()) && matches(prior, request)) {
                    return BookingRequestResponse.from(prior);
                }
                throw new DomainException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT",
                        "Idempotency key has already been used for a different booking request");
            }
        }

        BookingRequest bookingRequest = new BookingRequest(
                "BR-" + UUID.randomUUID(),
                BookingRequestType.SECRETARY_REQUEST,
                actor,
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
                BookingRequestStatus.DRAFT,
                idempotencyKey,
                now);
        return BookingRequestResponse.from(repository.saveAndFlush(bookingRequest));
    }

    @Transactional
    public BookingRequestResponse submit(UUID id) {
        UUID authenticatedActor = authorizationClient.authorizeCurrentCaller(
                new BookingAuthorizationRequest(AUTHENTICATE, null, null, null, null));
        BookingRequest bookingRequest = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "BOOKING_REQUEST_NOT_FOUND",
                        "Booking request was not found"));
        authorizationClient.authorizeCurrentCaller(new BookingAuthorizationRequest(REQUEST_READ,
                bookingRequest.getOfficeBuildingId(), null, bookingRequest.getRequestedByUserId(), null));
        if (bookingRequest.getStatus() != BookingRequestStatus.DRAFT) {
            throw new DomainException(HttpStatus.CONFLICT, "BOOKING_REQUEST_STATE_CONFLICT",
                    "Only a draft booking request can be submitted");
        }

        Instant now = clock.instant();
        ValidatedBookingReferences references = ownerServicesClient.validateRequest(
                new BookingRequestFacts(bookingRequest.getDepartmentId(), bookingRequest.getRoomId(),
                        bookingRequest.getOfficeBuildingId(), bookingRequest.getRecurrenceRuleId(),
                        bookingRequest.getRequestedStart(), bookingRequest.getRequestedEnd(),
                        bookingRequest.getAttendeeCount(), bookingRequest.getExternalGuests()),
                currentBearerToken(), now);
        if (!bookingRequest.getOfficeBuildingId().equals(references.officeBuildingId())
                || !bookingRequest.getDepartmentId().equals(references.departmentId())
                || !bookingRequest.getRoomId().equals(references.roomId())) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_OWNER_DATA_INCONSISTENT",
                    "Current owner-service data does not match the stored booking request");
        }

        UUID actor = authorizationClient.authorizeCurrentCaller(new BookingAuthorizationRequest(REQUEST_SUBMIT,
                references.officeBuildingId(), references.departmentId(), bookingRequest.getRequestedByUserId(),
                references.vipRoom()));
        if (!authenticatedActor.equals(actor)) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_AUTHORIZATION_INCONSISTENT",
                    "Identity returned inconsistent actors during booking authorization");
        }
        if (!bookingRequest.submitForApproval()) {
            throw new DomainException(HttpStatus.CONFLICT, "BOOKING_REQUEST_STATE_CONFLICT",
                    "Only a draft booking request can be submitted");
        }
        BookingRequest submitted = repository.saveAndFlush(bookingRequest);
        domainEvents.record(2, "BookingRequested", "BOOKING_REQUEST", submitted.getId(), submitted.getVersion(),
                now, List.of(), Map.of(
                        "requesterUserId", submitted.getRequestedByUserId().toString(),
                        "departmentId", submitted.getDepartmentId().toString(),
                        "vipRoom", references.vipRoom(),
                        "notificationRoute", "SCOPED_ADMIN_REQUEST",
                        "roomId", submitted.getRoomId().toString(),
                        "requestReference", submitted.getRequestReference()));
        return BookingRequestResponse.from(submitted);
    }

    @Transactional(readOnly = true)
    public BookingRequestPageResponse list(BookingRequestPageQuery query) {
        UUID actor = authorizationClient.authorizeCurrentCaller(new BookingAuthorizationRequest(
                REQUEST_LIST, query.officeBuildingId(), null, null, null));
        PageRequest pageRequest = PageRequest.of(query.pageNumber(), query.pageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<BookingRequest> page;
        if (query.officeBuildingId() != null) {
            page = query.status() == null
                    ? repository.findByOfficeBuildingId(query.officeBuildingId(), pageRequest)
                    : repository.findByOfficeBuildingIdAndStatus(query.officeBuildingId(), query.status(), pageRequest);
        } else {
            page = query.status() == null
                    ? repository.findByRequestedByUserId(actor, pageRequest)
                    : repository.findByRequestedByUserIdAndStatus(actor, query.status(), pageRequest);
        }
        return new BookingRequestPageResponse(page.getContent().stream().map(BookingRequestResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public BookingRequestResponse get(UUID id) {
        BookingRequest bookingRequest = repository.findById(id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "BOOKING_REQUEST_NOT_FOUND",
                        "Booking request was not found"));
        authorizationClient.authorizeCurrentCaller(new BookingAuthorizationRequest(REQUEST_READ,
                bookingRequest.getOfficeBuildingId(), null, bookingRequest.getRequestedByUserId(), null));
        return BookingRequestResponse.from(bookingRequest);
    }

    static void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must contain 1-128 ASCII letters, digits, dots, underscores, colons, or hyphens");
        }
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

    static String currentBearerToken() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                    && !authorization.substring(7).isBlank()) {
                return authorization;
            }
        }
        throw new DomainException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                "Authentication is required to validate booking references");
    }

    static boolean matches(BookingRequest existing, CreateBookingRequest request) {
        return existing.getDepartmentId().equals(request.departmentId())
                && existing.getRoomId().equals(request.roomId())
                && existing.getOfficeBuildingId().equals(request.officeBuildingId())
                && java.util.Objects.equals(existing.getRecurrenceRuleId(), request.recurrenceRuleId())
                && existing.getTitle().equals(request.title().trim())
                && java.util.Objects.equals(existing.getPurpose(), trimToNull(request.purpose()))
                && existing.getRequestedStart().equals(request.requestedStart())
                && existing.getRequestedEnd().equals(request.requestedEnd())
                && existing.getAttendeeCount() == request.attendeeCount()
                && java.util.Objects.equals(existing.getExternalGuests(), request.externalGuests());
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
