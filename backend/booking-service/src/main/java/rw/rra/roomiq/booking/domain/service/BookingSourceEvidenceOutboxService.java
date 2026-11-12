package rw.rra.roomiq.booking.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.booking.domain.entity.BookingRequest;
import rw.rra.roomiq.booking.domain.enums.BookingRequestStatus;
import rw.rra.roomiq.booking.domain.enums.BookingRequestType;
import rw.rra.roomiq.booking.domain.repository.BookingSourceEvidenceOutboxEntry;
import rw.rra.roomiq.booking.domain.repository.BookingSourceEvidenceOutboxRepository;
import rw.rra.roomiq.booking.integration.BookingSourceEvidenceProperties;
import rw.rra.roomiq.common.web.CorrelationIdFilter;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class BookingSourceEvidenceOutboxService {
    private static final Logger log = LoggerFactory.getLogger(BookingSourceEvidenceOutboxService.class);
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern START_BASIS = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    private final BookingSourceEvidenceOutboxRepository repository;
    private final BookingSourceEvidenceProperties properties;
    private final Clock clock;

    @Autowired
    public BookingSourceEvidenceOutboxService(BookingSourceEvidenceOutboxRepository repository,
                                              BookingSourceEvidenceProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    BookingSourceEvidenceOutboxService(BookingSourceEvidenceOutboxRepository repository,
                                       BookingSourceEvidenceProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(BookingRequest request, Instant occurredAt, Instant decisionAt) {
        if (request == null || request.getId() == null || occurredAt == null || request.getCreatedAt() == null) {
            throw new IllegalArgumentException("Booking source evidence requires a persisted request and timestamps");
        }
        long revision = Math.max(1L, (long) request.getVersion() + 1L);
        var existing = repository.findByRevision(request.getId(), revision);
        Instant closedAt = isClosed(request.getStatus()) ? occurredAt : null;
        Instant observedAt = clock.instant();
        if (existing.isPresent()) {
            var prior = existing.get();
            if (!prior.currentLifecycleStatus().equals(request.getStatus().name())
                    || !prior.occurredAt().equals(occurredAt)) {
                throw new IllegalStateException("Booking source revision was recorded with conflicting facts");
            }
            return;
        }

        BookingSourceEvidenceProperties.Mapping mapping = properties.mappingFor(request.getRequestType());
        String category = mapping == null ? null : normalize(mapping.getCategoryCode());
        String startBasis = mapping == null ? null : normalizeStartBasis(mapping.getStartBasisCode());
        Instant startAt = authoritativeStart(startBasis, request.getCreatedAt(), occurredAt, closedAt);
        boolean configured = category != null && startBasis != null && startAt != null;
        UUID eventId = UUID.randomUUID();
        UUID supersedesEventId = repository.latestEventId(request.getId()).orElse(null);
        repository.insert(request, revision, eventId, supersedesEventId, category, startBasis, startAt,
                occurredAt, observedAt, closedAt, decisionAt, MDC.get(CorrelationIdFilter.MDC_KEY),
                configured ? "PENDING" : "CONFIGURATION_BLOCKED");
    }

    public void applyCurrentMapping(BookingSourceEvidenceOutboxEntry entry) {
        BookingRequestType requestType;
        try {
            requestType = BookingRequestType.valueOf(entry.requestType());
        } catch (IllegalArgumentException exception) {
            repository.requireReconciliation(entry.id(), "UNSUPPORTED_BOOKING_REQUEST_TYPE");
            return;
        }
        BookingSourceEvidenceProperties.Mapping mapping = properties.mappingFor(requestType);
        if (mapping == null) {
            reportConfigurationBlock(entry, "BOOKING_CATEGORY_MAPPING_MISSING");
            return;
        }
        String category = normalize(mapping.getCategoryCode());
        String startBasis = normalizeStartBasis(mapping.getStartBasisCode());
        if (category == null) {
            reportConfigurationBlock(entry, "BOOKING_CATEGORY_MAPPING_INVALID");
            return;
        }
        if (startBasis == null) {
            reportConfigurationBlock(entry, "BOOKING_START_BASIS_MAPPING_INVALID");
            return;
        }
        Instant startAt = authoritativeStart(startBasis, entry.recordCreatedAt(), entry.occurredAt(),
                entry.closedAt());
        if (startAt == null) {
            reportConfigurationBlock(entry, "BOOKING_START_BASIS_TIMESTAMP_UNAVAILABLE");
            return;
        }
        repository.applyMapping(entry.id(), category, startBasis, startAt);
    }

    private void reportConfigurationBlock(BookingSourceEvidenceOutboxEntry entry, String diagnosticCode) {
        if (repository.recordConfigurationBlock(entry.id(), diagnosticCode)) {
            log.warn("Booking source evidence remains blocked requestId={} requestType={} errorCode={} "
                            + "configure an explicit category-code and supported start-basis-code mapping",
                    entry.bookingRequestId(), entry.requestType(), diagnosticCode);
        }
    }

    private static Instant authoritativeStart(String basis, Instant createdAt, Instant occurredAt, Instant closedAt) {
        if (basis == null) {
            return null;
        }
        return switch (basis) {
            case "RECORD_CREATED_AT" -> createdAt;
            case "OCCURRED_AT", "ORIGINAL_EVENT" -> occurredAt;
            case "CLOSED_AT" -> closedAt;
            default -> null;
        };
    }

    private static boolean isClosed(BookingRequestStatus status) {
        return status == BookingRequestStatus.REJECTED
                || status == BookingRequestStatus.CANCELLED
                || status == BookingRequestStatus.EXPIRED;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return CODE.matcher(normalized).matches() ? normalized : null;
    }

    private static String normalizeStartBasis(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!START_BASIS.matcher(normalized).matches()) {
            return null;
        }
        return switch (normalized) {
            case "RECORD_CREATED_AT", "OCCURRED_AT", "ORIGINAL_EVENT", "CLOSED_AT" -> normalized;
            default -> null;
        };
    }
}
