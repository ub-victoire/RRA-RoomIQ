package rw.rra.roomiq.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEvent {
    @Id
    private UUID id;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(nullable = false, length = 128)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "scope_office_building_id")
    private UUID scopeOfficeBuildingId;

    @Column(nullable = false, length = 32)
    private String outcome;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEvent() {
    }

    public AuditEvent(UUID id, UUID actorUserId, String action, String resourceType, UUID resourceId,
                      UUID scopeOfficeBuildingId, String outcome, String correlationId,
                      String metadata, Instant occurredAt) {
        this.id = id;
        this.actorUserId = actorUserId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.scopeOfficeBuildingId = scopeOfficeBuildingId;
        this.outcome = outcome;
        this.correlationId = correlationId;
        this.metadata = metadata;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public UUID getActorUserId() { return actorUserId; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public UUID getResourceId() { return resourceId; }
    public UUID getScopeOfficeBuildingId() { return scopeOfficeBuildingId; }
    public String getOutcome() { return outcome; }
    public String getCorrelationId() { return correlationId; }
    public String getMetadata() { return metadata; }
    public Instant getOccurredAt() { return occurredAt; }
}
