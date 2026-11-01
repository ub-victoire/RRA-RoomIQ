package rw.rra.roomiq.booking.domain.service;

import org.postgresql.util.PGobject;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import rw.rra.roomiq.common.web.CorrelationIdFilter;
import rw.rra.roomiq.booking.domain.entity.BookingEventOutboxEntry;
import rw.rra.roomiq.booking.domain.repository.BookingEventOutboxRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingDomainEventOutboxService {
    private static final String SOURCE_SERVICE = "booking-service";
    private static final int EVENT_VERSION = 1;

    private final BookingEventOutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public BookingDomainEventOutboxService(BookingEventOutboxRepository repository, ObjectMapper objectMapper) {
        this(repository, objectMapper, Clock.systemUTC());
    }

    BookingDomainEventOutboxService(BookingEventOutboxRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(String eventType, String aggregateType, UUID aggregateId, long aggregateVersion,
                       Instant occurredAt, List<UUID> recipients, Map<String, Object> facts) {
        return record(EVENT_VERSION, eventType, aggregateType, aggregateId, aggregateVersion, occurredAt,
                recipients, facts);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(int eventVersion, String eventType, String aggregateType, UUID aggregateId,
                       long aggregateVersion, Instant occurredAt, List<UUID> recipients,
                       Map<String, Object> facts) {
        if (eventVersion < 1 || eventType == null || aggregateType == null || aggregateId == null
                || aggregateVersion < 0
                || occurredAt == null || recipients == null || recipients.stream().anyMatch(java.util.Objects::isNull)
                || facts == null) {
            throw new IllegalArgumentException("Booking domain events require complete aggregate and recipient facts");
        }
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recipientUserIds", recipients.stream().map(UUID::toString).distinct().toList());
        payload.putAll(facts);
        BookingDomainEvent event = new BookingDomainEvent(eventId, SOURCE_SERVICE, eventType, eventVersion,
                aggregateType, aggregateId, aggregateVersion, MDC.get(CorrelationIdFilter.MDC_KEY),
                null, occurredAt, payload);
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize Booking domain event", exception);
        }

        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        try {
            jsonb.setValue(serialized);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not map Booking domain event to PostgreSQL JSONB", exception);
        }
        repository.save(new BookingEventOutboxEntry(eventId, eventType, eventVersion, aggregateType,
                aggregateId, aggregateVersion, jsonb, occurredAt, clock.instant()));
        return eventId;
    }

    private record BookingDomainEvent(UUID eventId, String sourceService, String eventType, int eventVersion,
                                      String aggregateType, UUID aggregateId, long aggregateVersion,
                                      String correlationId, String causationId, Instant occurredAt,
                                      Map<String, Object> payload) {
    }
}
