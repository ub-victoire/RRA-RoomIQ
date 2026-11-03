package rw.rra.roomiq.audit.domain;

import org.springframework.http.HttpStatus;
import rw.rra.roomiq.common.web.DomainException;

import java.time.Instant;
import java.util.UUID;

public record AuditEventSearchCriteria(
        String action,
        String resourceType,
        UUID resourceId,
        UUID actorUserId,
        String outcome,
        String correlationId,
        UUID buildingId,
        Instant occurredFrom,
        Instant occurredTo,
        Integer page,
        Integer size,
        String sortBy,
        String sortDirection) {

    public AuditEventSearchCriteria {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
        sortBy = sortBy == null ? "occurredAt" : sortBy;
        sortDirection = sortDirection == null ? "DESC" : sortDirection;

        if (page < 0 || size < 1 || size > 100) {
            throw invalidRequest("Page must be non-negative and size must be between 1 and 100");
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw invalidRequest("Requested page offset cannot be represented by the database query API");
        }
        if (occurredFrom != null && occurredTo != null && !occurredFrom.isBefore(occurredTo)) {
            throw invalidRequest("occurredFrom must be earlier than occurredTo");
        }
        if (!"ASC".equalsIgnoreCase(sortDirection) && !"DESC".equalsIgnoreCase(sortDirection)) {
            throw invalidRequest("sortDirection must be ASC or DESC");
        }
        sortDirection = sortDirection.toUpperCase(java.util.Locale.ROOT);
    }

    private static DomainException invalidRequest(String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }
}
