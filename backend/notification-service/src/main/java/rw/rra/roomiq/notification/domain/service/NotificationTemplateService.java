package rw.rra.roomiq.notification.domain.service;

import jakarta.persistence.EntityManager;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import rw.rra.roomiq.common.web.CorrelationIdFilter;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.notification.domain.dto.NotificationTemplateActivationRequest;
import rw.rra.roomiq.notification.domain.dto.NotificationTemplateCreateRequest;
import rw.rra.roomiq.notification.domain.dto.NotificationTemplateResponse;
import rw.rra.roomiq.notification.domain.entity.NotificationAuditOutboxEntry;
import rw.rra.roomiq.notification.domain.entity.NotificationTemplate;
import rw.rra.roomiq.notification.domain.repository.NotificationAuditOutboxRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationTemplateRepository;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationTemplateService {
    private final NotificationTemplateRepository templateRepository;
    private final NotificationAuditOutboxRepository outboxRepository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public NotificationTemplateService(NotificationTemplateRepository templateRepository,
                                       NotificationAuditOutboxRepository outboxRepository,
                                       EntityManager entityManager, ObjectMapper objectMapper) {
        this(templateRepository, outboxRepository, entityManager, objectMapper, Clock.systemUTC());
    }

    NotificationTemplateService(NotificationTemplateRepository templateRepository,
                                NotificationAuditOutboxRepository outboxRepository,
                                EntityManager entityManager,
                                ObjectMapper objectMapper, Clock clock) {
        this.templateRepository = templateRepository;
        this.outboxRepository = outboxRepository;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<NotificationTemplateResponse> list() {
        return templateRepository.findAllByOrderByCodeAscChannelAscVersionDesc().stream()
                .map(NotificationTemplateService::toResponse).toList();
    }

    @Transactional
    public NotificationTemplateResponse create(NotificationTemplateCreateRequest request, UUID actorUserId) {
        validateRequest(request);
        if (templateRepository.existsByCodeAndVersionAndChannel(request.code(), request.version(), request.channel())) {
            throw conflict("A template with this code, version, and channel already exists");
        }
        NotificationTemplate template = new NotificationTemplate(UUID.randomUUID(), request.code(),
                request.version(), request.channel(), request.subject(), request.bodyTemplate());
        try {
            templateRepository.saveAndFlush(template);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("A template with this code, version, and channel already exists");
        }
        recordAudit("NotificationTemplateCreated", actorUserId, template, List.of(
                "code", "version", "channel", "subject", "bodyTemplate"));
        return toResponse(template);
    }

    @Transactional
    public NotificationTemplateResponse setActive(UUID templateId, NotificationTemplateActivationRequest request,
                                                  UUID actorUserId) {
        lockTemplateFamily(templateId);
        NotificationTemplate template = templateRepository.findByIdForUpdate(templateId)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "NOTIFICATION_TEMPLATE_NOT_FOUND",
                        "Notification template was not found"));
        boolean active = request.active();
        if (template.isActive() == active) {
            return toResponse(template);
        }
        if (active) {
            templateRepository.deactivateActiveVersions(template.getCode(), template.getChannel());
            template.activate();
        } else {
            template.deactivate();
        }
        templateRepository.saveAndFlush(template);
        recordAudit(active ? "NotificationTemplateActivated" : "NotificationTemplateDeactivated",
                actorUserId, template, List.of("isActive"));
        return toResponse(template);
    }

    private void lockTemplateFamily(UUID templateId) {
        NotificationTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "NOTIFICATION_TEMPLATE_NOT_FOUND",
                        "Notification template was not found"));
        entityManager.createNativeQuery("""
                SELECT pg_advisory_xact_lock(hashtextextended(CAST(:templateFamily AS text), 0))
                """)
                .setParameter("templateFamily", template.getCode() + ":" + template.getChannel())
                .getSingleResult();
    }

    private void recordAudit(String eventType, UUID actorUserId, NotificationTemplate template,
                             List<String> changedFields) {
        Instant now = clock.instant();
        UUID eventId = UUID.randomUUID();
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("code", template.getCode());
        metadata.put("version", template.getVersion());
        metadata.put("channel", template.getChannel());
        metadata.put("changedFields", changedFields);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("schemaVersion", 1);
        event.put("eventType", eventType);
        event.put("timestamp", now);
        event.put("sourceService", "notification-service");
        event.put("actorUserId", actorUserId);
        event.put("resourceType", "NOTIFICATION_TEMPLATE");
        event.put("resourceId", template.getId());
        event.put("correlationId", correlationId);
        event.put("metadata", metadata);
        try {
            outboxRepository.save(new NotificationAuditOutboxEntry(eventId, eventType, actorUserId,
                    template.getId(), correlationId, objectMapper.writeValueAsString(event), now));
        } catch (Exception exception) {
            throw new IllegalStateException("Notification template audit event could not be recorded", exception);
        }
    }

    private void validateRequest(NotificationTemplateCreateRequest request) {
        NotificationTemplateRenderer.validateSyntax(request.bodyTemplate());
        if (request.subject() != null) {
            NotificationTemplateRenderer.validateSyntax(request.subject());
        }
        if ("EMAIL".equals(request.channel())
                && (request.subject() == null || request.subject().isBlank())) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "NOTIFICATION_TEMPLATE_INVALID",
                    "Email templates require a subject");
        }
    }

    private static NotificationTemplateResponse toResponse(NotificationTemplate template) {
        return new NotificationTemplateResponse(template.getId(), template.getCode(), template.getVersion(),
                template.getChannel(), template.getSubject(), template.getBodyTemplate(), template.isActive());
    }

    private static DomainException conflict(String message) {
        return new DomainException(HttpStatus.CONFLICT, "NOTIFICATION_TEMPLATE_VERSION_CONFLICT", message);
    }
}
