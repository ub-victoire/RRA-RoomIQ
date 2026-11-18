package rw.rra.roomiq.audit.domain;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Objects;

@Service
public class NotificationTemplateAuditEventProcessor {
    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;
    private final AuditSourceEvidenceOutboxService sourceEvidenceOutbox;

    public NotificationTemplateAuditEventProcessor(AuditEventRepository repository, ObjectMapper objectMapper,
                                                   AuditSourceEvidenceOutboxService sourceEvidenceOutbox) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.sourceEvidenceOutbox = sourceEvidenceOutbox;
    }

    @Transactional
    public void process(NotificationTemplateAuditEvent event) {
        int inserted = repository.insertIfAbsent(event.eventId(), event.actorUserId(), event.eventType(),
                event.resourceType(), event.resourceId(), null, "SUCCESS", event.correlationId(),
                event.metadata(), event.timestamp(), Instant.now());
        if (inserted > 0) {
            AuditEvent persisted = repository.findById(event.eventId())
                    .orElseThrow(() -> new IllegalStateException("Audit event disappeared after insertion"));
            sourceEvidenceOutbox.record(persisted);
            return;
        }
        AuditEvent existing = repository.findById(event.eventId())
                .orElseThrow(() -> new IllegalStateException("Audit event disappeared after deduplication"));
        if (!matches(existing, event)) {
            throw new IllegalArgumentException("Audit event identifier was reused with different content");
        }
    }

    private boolean matches(AuditEvent existing, NotificationTemplateAuditEvent incoming) {
        try {
            JsonNode savedMetadata = objectMapper.readTree(existing.getMetadata());
            JsonNode incomingMetadata = objectMapper.readTree(incoming.metadata());
            return Objects.equals(existing.getActorUserId(), incoming.actorUserId())
                    && Objects.equals(existing.getAction(), incoming.eventType())
                    && Objects.equals(existing.getResourceType(), incoming.resourceType())
                    && Objects.equals(existing.getResourceId(), incoming.resourceId())
                    && Objects.equals(existing.getScopeOfficeBuildingId(), null)
                    && Objects.equals(existing.getOutcome(), "SUCCESS")
                    && Objects.equals(existing.getCorrelationId(), incoming.correlationId())
                    && Objects.equals(existing.getOccurredAt(), incoming.timestamp())
                    && Objects.equals(savedMetadata, incomingMetadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored audit event could not be verified", exception);
        }
    }
}
