package rw.rra.roomiq.notification.domain.service;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.notification.domain.entity.Notification;
import rw.rra.roomiq.notification.domain.entity.NotificationEventReceipt;
import rw.rra.roomiq.notification.domain.entity.NotificationTemplate;
import rw.rra.roomiq.notification.domain.event.InvalidNotificationEventException;
import rw.rra.roomiq.notification.domain.event.NotificationEvent;
import rw.rra.roomiq.notification.domain.repository.NotificationEventReceiptRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationRepository;
import rw.rra.roomiq.notification.domain.repository.NotificationTemplateRepository;
import rw.rra.roomiq.notification.integration.NotificationIdentityRecipientClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class NotificationEventProcessingService {
    private static final String RESERVATION_RESOURCE = "RESERVATION";
    private static final Set<String> TERMINAL_EVENT_TYPES = Set.of(
            "BookingCancelled", "MeetingCompleted", "MeetingReleased", "AutomaticReleaseOrNoShow");
    private static final int REMINDER_BATCH_SIZE = 100;

    private final NotificationEventReceiptRepository receiptRepository;
    private final NotificationRepository notificationRepository;
    private final EntityManager entityManager;
    private final Clock clock;
    private final NotificationIdentityRecipientClient identityRecipientClient;
    private final NotificationPreferenceService preferenceService;
    private final NotificationTemplateRepository templateRepository;
    private final NotificationTemplateRenderer templateRenderer;
    private final ObjectMapper objectMapper;

    @Autowired
    public NotificationEventProcessingService(NotificationEventReceiptRepository receiptRepository,
                                              NotificationRepository notificationRepository,
                                              EntityManager entityManager,
                                              NotificationIdentityRecipientClient identityRecipientClient,
                                              NotificationPreferenceService preferenceService,
                                              NotificationTemplateRepository templateRepository,
                                              NotificationTemplateRenderer templateRenderer,
                                              ObjectMapper objectMapper) {
        this(receiptRepository, notificationRepository, entityManager, Clock.systemUTC(),
                identityRecipientClient, preferenceService, templateRepository, templateRenderer, objectMapper);
    }

    NotificationEventProcessingService(NotificationEventReceiptRepository receiptRepository,
                                       NotificationRepository notificationRepository, EntityManager entityManager,
                                       Clock clock,
                                       NotificationIdentityRecipientClient identityRecipientClient,
                                       NotificationPreferenceService preferenceService,
                                       NotificationTemplateRepository templateRepository,
                                       NotificationTemplateRenderer templateRenderer,
                                       ObjectMapper objectMapper) {
        this.receiptRepository = receiptRepository;
        this.notificationRepository = notificationRepository;
        this.entityManager = entityManager;
        this.clock = clock;
        this.identityRecipientClient = identityRecipientClient;
        this.preferenceService = preferenceService;
        this.templateRepository = templateRepository;
        this.templateRenderer = templateRenderer;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProcessingResult process(NotificationEvent event) {
        if (preferenceService == null) {
            throw new IllegalStateException("Notification preference service is required for event processing");
        }
        if (isReservationReminder(event) || isTerminalForReservation(event)) {
            lockReservation(event.resourceId());
        }
        int inserted = receiptRepository.insertIfAbsent(UUID.randomUUID(), event.eventId(),
                event.sourceService(), event.eventType(), event.eventVersion(), event.aggregateType(),
                event.aggregateId(), event.aggregateVersion(), event.correlationId(), event.causationId(),
                event.resourceType(), event.resourceId(), clock.instant());
        if (inserted == 0) {
            NotificationEventReceipt existing = receiptRepository.findByEventId(event.eventId())
                    .orElseThrow(() -> new IllegalStateException("Event receipt disappeared after deduplication"));
            if (!existing.matches(toReceipt(event))) {
                throw new InvalidNotificationEventException(
                        "Notification event identifier was reused with different event metadata");
            }
            return ProcessingResult.DUPLICATE;
        }

        List<UUID> recipients = resolveRecipients(event);
        Instant now = clock.instant();
        if (isTerminalForReservation(event)) {
            notificationRepository.cancelScheduledReminders(
                    RESERVATION_RESOURCE, event.resourceId(), now);
        }
        if (isReservationReminder(event) && hasTerminalReservationEvent(event.resourceId())) {
            return ProcessingResult.PROCESSED_WITHOUT_NOTIFICATIONS;
        }

        List<UUID> inAppRecipients = preferenceService.filterRecipients(event.eventType(), recipients, "IN_APP");
        List<UUID> emailRecipients = preferenceService.filterRecipients(event.eventType(), recipients, "EMAIL");
        if (isReservationReminder(event)) {
            Instant scheduledAt = Instant.parse(event.payload().get("reminderAt").asText());
            RenderedNotification renderedInApp = inAppRecipients.isEmpty()
                    ? null : renderForEvent(event, "IN_APP");
            for (UUID recipient : inAppRecipients) {
                notificationRepository.insertScheduledReminderIfAbsent(
                        UUID.randomUUID(), recipient, RESERVATION_RESOURCE, event.resourceId(),
                        "IN_APP", renderedInApp.templateId(), renderedInApp.payload(), scheduledAt,
                        event.occurredAt());
            }
            RenderedNotification renderedEmail = emailRecipients.isEmpty()
                    ? null : renderForEvent(event, "EMAIL");
            for (UUID recipient : emailRecipients) {
                notificationRepository.insertScheduledReminderIfAbsent(
                        UUID.randomUUID(), recipient, RESERVATION_RESOURCE, event.resourceId(),
                        "EMAIL", renderedEmail.templateId(), renderedEmail.payload(), scheduledAt,
                        event.occurredAt());
            }
            return inAppRecipients.isEmpty() && emailRecipients.isEmpty()
                    ? ProcessingResult.PROCESSED_WITHOUT_NOTIFICATIONS : ProcessingResult.PROCESSED;
        }

        RenderedNotification renderedInApp = inAppRecipients.isEmpty() ? null : renderForEvent(event, "IN_APP");
        RenderedNotification renderedEmail = emailRecipients.isEmpty() ? null : renderForEvent(event, "EMAIL");
        List<Notification> notifications = inAppRecipients.stream()
                .map(recipient -> createNotification(event, recipient, "IN_APP", renderedInApp, now))
                .toList();
        List<Notification> emailNotifications = emailRecipients.stream()
                .map(recipient -> createNotification(event, recipient, "EMAIL", renderedEmail, now))
                .toList();
        notificationRepository.saveAll(notifications);
        notificationRepository.saveAll(emailNotifications);
        return notifications.isEmpty() && emailNotifications.isEmpty()
                ? ProcessingResult.PROCESSED_WITHOUT_NOTIFICATIONS
                : ProcessingResult.PROCESSED;
    }

    private static Notification createNotification(NotificationEvent event, UUID recipient,
                                                   String channel, RenderedNotification rendered, Instant now) {
        Notification notification = new Notification(UUID.randomUUID(), recipient, event.eventType(),
                event.resourceType(), event.resourceId(), channel, rendered.payload(), now, "PENDING");
        notification.setNotificationTemplateId(rendered.templateId());
        return notification;
    }

    private List<UUID> resolveRecipients(NotificationEvent event) {
        if (Set.of("AutomaticReleaseOrNoShow", "MeetingCompleted", "MeetingReleased")
                .contains(event.eventType())) {
            return List.of();
        }
        if ("BookingRequested".equals(event.eventType()) || "ExtensionRequested".equals(event.eventType())) {
            if (event.eventVersion() == 1
                    || "NONE".equals(event.payload().path("notificationRoute").asText())) {
                return List.of();
            }
            if (!"SCOPED_ADMIN_REQUEST".equals(event.payload().path("notificationRoute").asText())
                    || identityRecipientClient == null) {
                throw new IllegalStateException("Scoped Admin notification routing is unavailable");
            }
            return identityRecipientClient.resolveScopedAdmins(event);
        }
        return event.recipientUserIds();
    }

    @Transactional
    public int deliverDueReminders() {
        Instant now = clock.instant();
        List<UUID> resourceIds = notificationRepository.findDueReminderResourceIds(
                RESERVATION_RESOURCE, now, REMINDER_BATCH_SIZE);
        int delivered = 0;
        for (UUID resourceId : resourceIds) {
            lockReservation(resourceId);
            List<Notification> due = notificationRepository.findDueRemindersForUpdate(
                    RESERVATION_RESOURCE, resourceId, now);
            if (hasTerminalReservationEvent(resourceId)) {
                due.forEach(notification -> notification.cancel(now));
            } else {
                List<UUID> reminderRecipients = due.stream()
                        .map(Notification::getRecipientUserId).distinct().toList();
                Set<UUID> inAppRecipients = Set.copyOf(preferenceService.filterRecipients(
                        "Reminder", reminderRecipients, "IN_APP"));
                Set<UUID> emailRecipients = Set.copyOf(preferenceService.filterRecipients(
                        "Reminder", reminderRecipients, "EMAIL"));
                for (Notification notification : due) {
                    boolean enabled = "IN_APP".equals(notification.getChannel())
                            ? inAppRecipients.contains(notification.getRecipientUserId())
                            : emailRecipients.contains(notification.getRecipientUserId());
                    if (enabled) {
                        notification.makePending();
                        delivered++;
                    } else {
                        notification.cancel(now);
                    }
                }
            }
            notificationRepository.saveAllAndFlush(due);
        }
        return delivered;
    }

    private boolean hasTerminalReservationEvent(UUID reservationId) {
        return receiptRepository.existsByResourceTypeAndResourceIdAndEventTypeIn(
                RESERVATION_RESOURCE, reservationId, TERMINAL_EVENT_TYPES);
    }

    private void lockReservation(UUID reservationId) {
        entityManager.createNativeQuery("""
                SELECT pg_advisory_xact_lock(hashtextextended(CAST(:reservationId AS text), 0))
                """)
                .setParameter("reservationId", reservationId.toString())
                .getSingleResult();
    }

    private static boolean isReservationReminder(NotificationEvent event) {
        return "Reminder".equals(event.eventType())
                && RESERVATION_RESOURCE.equals(event.resourceType())
                && event.resourceId() != null;
    }

    private static boolean isTerminalForReservation(NotificationEvent event) {
        return TERMINAL_EVENT_TYPES.contains(event.eventType())
                && RESERVATION_RESOURCE.equals(event.resourceType())
                && event.resourceId() != null;
    }

    private NotificationEventReceipt toReceipt(NotificationEvent event) {
        return new NotificationEventReceipt(UUID.randomUUID(), event.eventId(), event.sourceService(),
                event.eventType(), event.eventVersion(), event.aggregateType(), event.aggregateId(),
                event.aggregateVersion(), event.correlationId(), event.causationId(),
                event.resourceType(), event.resourceId(), clock.instant());
    }

    private RenderedNotification renderForEvent(NotificationEvent event, String channel) {
        if (templateRepository == null || templateRenderer == null || objectMapper == null) {
            throw new IllegalStateException("Notification template rendering is not configured");
        }
        NotificationTemplate template = templateRepository.findByCodeAndChannelAndActiveTrue(
                        event.eventType(), channel)
                .orElseThrow(() -> new IllegalStateException(
                        "Active " + channel + " template is unavailable for event type " + event.eventType()));
        Map<String, Object> safe = safeDisplayFacts(event);
        NotificationTemplateRenderer.RenderedTemplate rendered = templateRenderer.render(template, safe);
        safe.put("subject", rendered.subject());
        safe.put("message", rendered.body());
        safe.put("templateCode", template.getCode());
        safe.put("templateVersion", template.getVersion());
        try {
            return new RenderedNotification(template.getId(), objectMapper.writeValueAsString(safe));
        } catch (Exception exception) {
            throw new IllegalStateException("Rendered notification payload could not be persisted", exception);
        }
    }

    private static Map<String, Object> safeDisplayFacts(NotificationEvent event) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("eventType", event.eventType());
        safe.put("sourceService", event.sourceService());
        safe.put("resourceType", event.resourceType());
        safe.put("resourceId", event.resourceId() == null ? null : event.resourceId().toString());
        safe.put("aggregateType", event.aggregateType());
        safe.put("aggregateId", event.aggregateId().toString());
        safe.put("occurredAt", event.occurredAt().toString());
        if (event.correlationId() != null) {
            safe.put("correlationId", event.correlationId());
        }

        JsonNode payload = event.payload();
        if (payload != null && payload.isObject()) {
            for (String safeField : List.of("reservationId", "meetingId", "roomId", "reasonCode",
                    "decision", "decisionComment", "status", "action", "reminderOffsetMinutes", "timezone",
                    "occurrenceStartsAt", "reminderAt", "releasedAt", "completedAt", "periodStart", "periodEnd")) {
                JsonNode value = payload.get(safeField);
                if (value == null || value.isNull()) {
                    continue;
                }
                if (value.isTextual()) {
                    safe.put(safeField, value.asText());
                } else if (value.isNumber() || value.isBoolean()) {
                    safe.put(safeField, value.asText());
                }
            }
        }

        return safe;
    }

    private record RenderedNotification(UUID templateId, String payload) {
    }

    public enum ProcessingResult {
        PROCESSED,
        PROCESSED_WITHOUT_NOTIFICATIONS,
        DUPLICATE
    }
}
