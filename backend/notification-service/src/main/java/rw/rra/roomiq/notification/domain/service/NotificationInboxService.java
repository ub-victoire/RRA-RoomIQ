package rw.rra.roomiq.notification.domain.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import rw.rra.roomiq.notification.domain.dto.NotificationInboxItemResponse;
import rw.rra.roomiq.notification.domain.dto.NotificationInboxPageResponse;
import rw.rra.roomiq.notification.domain.entity.Notification;
import rw.rra.roomiq.notification.domain.repository.NotificationRepository;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class NotificationInboxService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    public NotificationInboxService(NotificationRepository notificationRepository, ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public NotificationInboxPageResponse list(Authentication authentication,
                                            String eventType,
                                            String status,
                                            Boolean read,
                                            Integer page,
                                            Integer size,
                                            String sortBy,
                                            String sortDirection) {
        UUID recipientUserId = authenticatedUserId(authentication);
        int pageIndex = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDirection)
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        String resolvedSort = (sortBy == null || sortBy.isBlank()) ? "createdAt" : sortBy;
        Pageable pageable = PageRequest.of(pageIndex, pageSize, Sort.by(direction, resolvedSort));

        Specification<Notification> specification = (root, query, cb) -> cb.equal(root.get("recipientUserId"), recipientUserId);
        if (eventType != null && !eventType.isBlank()) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("eventType"), eventType));
        }
        if (status != null && !status.isBlank()) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), status));
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
    public NotificationInboxItemResponse markRead(Authentication authentication, UUID notificationId) {
        UUID recipientUserId = authenticatedUserId(authentication);
        Notification notification = notificationRepository.findByIdAndRecipientUserId(notificationId, recipientUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Notification not found for this user"));
        if (notification.getReadAt() == null) {
            notification.markRead(Instant.now());
            notificationRepository.save(notification);
        }
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
                parsePayload(notification.getPayload())
        );
    }

    private Map<String, Object> parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            Object value = objectMapper.readValue(payload, Object.class);
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((k, v) -> result.put(String.valueOf(k), v));
                return result;
            }
            return Map.of("value", value);
        } catch (Exception exception) {
            return Map.of("raw", payload);
        }
    }

    private static UUID authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Map<?, ?> map) {
            Object subject = map.get("sub");
            if (subject != null) {
                return parseUuid(subject.toString());
            }
        }
        if (principal != null) {
            try {
                Object subject = principal.getClass().getMethod("getSubject").invoke(principal);
                if (subject != null) {
                    return parseUuid(subject.toString());
                }
            } catch (ReflectiveOperationException ignored) {
                // ignored: fallback below uses the authentication name
            }
        }
        return parseUuid(authentication.getName());
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Authenticated user identity is not a valid UUID");
        }
    }
}
