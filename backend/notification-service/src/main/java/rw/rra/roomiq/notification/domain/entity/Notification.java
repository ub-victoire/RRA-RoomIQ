package rw.rra.roomiq.notification.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification")
public class Notification {
    @Id
    private UUID id;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private UUID recipientUserId;

    @Column(name = "notification_template_id")
    private UUID notificationTemplateId;

    @Column(name = "event_type", nullable = false, length = 128, updatable = false)
    private String eventType;

    @Column(name = "resource_type", length = 64, updatable = false)
    private String resourceType;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private String channel;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private String payload;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
    }

    public Notification(UUID id, UUID recipientUserId, String eventType, String resourceType,
                        UUID resourceId, Instant createdAt) {
        this.id = id;
        this.recipientUserId = recipientUserId;
        this.eventType = eventType;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.channel = "IN_APP";
        this.status = "PENDING";
        this.createdAt = createdAt;
    }

    public Notification(UUID id, UUID recipientUserId, String eventType, String resourceType,
                        UUID resourceId, String payload, Instant scheduledAt, Instant createdAt) {
        this.id = id;
        this.recipientUserId = recipientUserId;
        this.eventType = eventType;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.channel = "IN_APP";
        this.status = "SCHEDULED";
        this.payload = payload;
        this.scheduledAt = scheduledAt;
        this.createdAt = createdAt;
    }

    public Notification(UUID id, UUID recipientUserId, String eventType, String resourceType,
                        UUID resourceId, String payload, Instant createdAt, String status) {
        this(id, recipientUserId, eventType, resourceType, resourceId, "IN_APP", payload, createdAt, status);
    }

    public Notification(UUID id, UUID recipientUserId, String eventType, String resourceType,
                        UUID resourceId, String channel, String payload, Instant createdAt, String status) {
        this.id = id;
        this.recipientUserId = recipientUserId;
        this.eventType = eventType;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.channel = channel;
        this.payload = payload;
        this.createdAt = createdAt;
        this.status = status;
    }

    public UUID getId() { return id; }
    public UUID getRecipientUserId() { return recipientUserId; }
    public String getEventType() { return eventType; }
    public UUID getNotificationTemplateId() { return notificationTemplateId; }
    public String getResourceType() { return resourceType; }
    public UUID getResourceId() { return resourceId; }
    public String getChannel() { return channel; }
    public String getStatus() { return status; }
    public Instant getScheduledAt() { return scheduledAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getPayload() { return payload; }
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setNotificationTemplateId(UUID notificationTemplateId) {
        this.notificationTemplateId = notificationTemplateId;
    }

    public void markRead(Instant readAt) {
        if (readAt == null) {
            throw new IllegalArgumentException("Read timestamp is required");
        }
        this.readAt = readAt;
    }

    public void makePending() {
        if (!"SCHEDULED".equals(status)) {
            throw new IllegalStateException("Only a scheduled notification can become pending");
        }
        status = "PENDING";
        scheduledAt = null;
    }

    public void cancel(Instant cancelledAt) {
        if (!"SCHEDULED".equals(status) || cancelledAt == null) {
            throw new IllegalStateException("Only a scheduled notification can be cancelled");
        }
        status = "CANCELLED";
        scheduledAt = null;
        this.cancelledAt = cancelledAt;
    }

    public void markEmailSent() {
        if (!"EMAIL".equals(channel) || !"PENDING".equals(status)) {
            throw new IllegalStateException("Only a pending email notification can be marked sent");
        }
        status = "SENT";
    }

    public void scheduleEmailRetry(Instant retryAt) {
        if (!"EMAIL".equals(channel) || !"PENDING".equals(status) || retryAt == null) {
            throw new IllegalStateException("Only a pending email notification can be scheduled for retry");
        }
        status = "SCHEDULED";
        scheduledAt = retryAt;
    }

    public void prepareScheduledEmailRetry() {
        if (!"EMAIL".equals(channel) || !"SCHEDULED".equals(status) || scheduledAt == null) {
            throw new IllegalStateException("Only a scheduled email retry can become pending");
        }
        status = "PENDING";
        scheduledAt = null;
    }

    public void markEmailDeadLettered() {
        if (!"EMAIL".equals(channel) || !"PENDING".equals(status)) {
            throw new IllegalStateException("Only a pending email notification can be dead-lettered");
        }
        status = "DEAD_LETTER";
        scheduledAt = null;
    }

    public void redriveEmail(Instant scheduledAt) {
        if (!"EMAIL".equals(channel) || !"DEAD_LETTER".equals(status) || scheduledAt == null) {
            throw new IllegalStateException("Only a dead-lettered email notification can be redriven");
        }
        status = "SCHEDULED";
        this.scheduledAt = scheduledAt;
    }
}
