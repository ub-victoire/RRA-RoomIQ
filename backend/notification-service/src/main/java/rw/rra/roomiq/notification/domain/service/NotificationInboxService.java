package rw.rra.roomiq.notification.domain.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.notification.domain.dto.NotificationInboxItemResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationInboxPageResponse;
import rw.rra.roomiq.notification.domain.entity.Notification;
import rw.rra.roomiq.notification.domain.repository.NotificationRepository;
import rw.rra.roomiq.notification.integration.NotificationIdentityAuthenticationClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class NotificationInboxService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "PENDING", "SENT", "FAILED", "DEAD_LETTER", "SCHEDULED", "CANCELLED");
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("createdAt", "readAt", "eventType", "status", "id");
    private static final Set<String> DISPLAY_PAYLOAD_FIELDS = Set.of(
            "eventType", "sourceService", "resourceType", "resourceId", "aggregateType", "aggregateId",
            "occurredAt", "correlationId", "reservationId", "meetingId", "roomId", "reasonCode", "decision",
            "decisionComment", "status", "action", "reminderOffsetMinutes", "timezone", "occurrenceStartsAt",
            "reminderAt", "releasedAt", "completedAt", "periodStart", "periodEnd",
            "subject", "message", "templateCode", "templateVersion");

    private final NotificationRepository notificationRepository;
    private final NotificationIdentityAuthenticationClient identityAuthenticationClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public NotificationInboxService(NotificationRepository notificationRepository,
                                    NotificationIdentityAuthenticationClient identityAuthenticationClient,
                                    ObjectMapper objectMapper) {
        this(notificationRepository, identityAuthenticationClient, objectMapper, Clock.systemUTC());
    }

    NotificationInboxService(NotificationRepository notificationRepository,
                             NotificationIdentityAuthenticationClient identityAuthenticationClient,
                             ObjectMapper objectMapper, Clock clock) {
        this.notificationRepository = notificationRepository;
        this.identityAuthenticationClient = identityAuthenticationClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotificationInboxPageResponse list(String authorization,
                                              String eventType,
                                              String status,
                                              Boolean read,
                                              Integer page,
                                              Integer size,
                                              String sortBy,
                                              String sortDirection) {
        UUID recipientUserId = identityAuthenticationClient.authenticate(authorization);
        int pageIndex = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageIndex < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw invalidFilter("Page must be non-negative and size must be between 1 and 100");
        }

        String normalizedEventType = normalizeEventType(eventType);
        String normalizedStatus = normalizeStatus(status);
        String resolvedSort = normalizeSortField(sortBy);
        Sort.Direction direction = normalizeSortDirection(sortDirection);
        Sort sort = Sort.by(direction, resolvedSort).and(Sort.by(Sort.Direction.DESC, "id"));
        Pageable pageable = PageRequest.of(pageIndex, pageSize, sort);

        Specification<Notification> specification =
                (root, query, cb) -> cb.and(
                        cb.equal(root.get("recipientUserId"), recipientUserId),
                        cb.equal(root.get("channel"), "IN_APP"));
        if (normalizedEventType != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.equal(root.get("eventType"), normalizedEventType));
        }
        if (normalizedStatus != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.equal(root.get("status"), normalizedStatus));
        }
        if (read != null) {
            specification = specification.and((root, query, cb) -> {
                Predicate predicate = read ? cb.isNotNull(root.get("readAt")) : cb.isNull(root.get("readAt"));
                return predicate;
            });
        }

        Page<Notification> result = notificationRepository.findAll(specification, pageable);
        return new NotificationInboxPageResponse(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getTotalElements(),
                result.getNumber(),
                result.getSize(),
                result.hasNext());
    }

    @Transactional
    public NotificationInboxItemResponse markRead(String authorization, UUID notificationId) {
        UUID recipientUserId = identityAuthenticationClient.authenticate(authorization);
        notificationRepository.markReadIfOwned(notificationId, recipientUserId, clock.instant());
        Notification notification = notificationRepository.findByIdAndRecipientUserIdAndChannel(
                        notificationId, recipientUserId, "IN_APP")
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND",
                        "Notification was not found"));
        return toResponse(notification);
    }

    private NotificationInboxItemResponse toResponse(Notification notification) {
        return new NotificationInboxItemResponse(
                notification.getId(),
                notification.getRecipientUserId(),
                notification.getEventType(),
                notification.getResourceType(),
                notification.getResourceId(),
                notification.getStatus(),
                notification.getCreatedAt(),
                notification.getReadAt(),
                notification.getScheduledAt(),
                notification.getCancelledAt(),
                parseDisplayPayload(notification.getPayload())
        );
    }

    private Map<String, Object> parseDisplayPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            Object value = objectMapper.readValue(payload, Object.class);
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalStateException("Persisted notification display payload must be a JSON object");
            }
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key instanceof String field && DISPLAY_PAYLOAD_FIELDS.contains(field)) {
                    safe.put(field, item);
                }
            });
            return Collections.unmodifiableMap(safe);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Persisted notification display payload is malformed", exception);
        }
    }

    private static String normalizeEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return null;
        }
        String normalized = eventType.trim();
        if (normalized.length() > 128) {
            throw invalidFilter("eventType must not exceed 128 characters");
        }
        return normalized;
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw invalidFilter("status is not a supported notification state");
        }
        return normalized;
    }

    private static String normalizeSortField(String sortBy) {
        String normalized = sortBy == null || sortBy.isBlank() ? "createdAt" : sortBy.trim();
        if (!ALLOWED_SORT_FIELDS.contains(normalized)) {
            throw invalidFilter("sortBy is not a supported notification field");
        }
        return normalized;
    }

    private static Sort.Direction normalizeSortDirection(String sortDirection) {
        if (sortDirection == null || sortDirection.isBlank()
                || "desc".equalsIgnoreCase(sortDirection.trim())) {
            return Sort.Direction.DESC;
        }
        if ("asc".equalsIgnoreCase(sortDirection.trim())) {
            return Sort.Direction.ASC;
        }
        throw invalidFilter("sortDirection must be asc or desc");
    }

    private static DomainException invalidFilter(String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, "INVALID_NOTIFICATION_FILTER", message);
    }
}
