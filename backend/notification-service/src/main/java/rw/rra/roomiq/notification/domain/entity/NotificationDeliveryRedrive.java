package rw.rra.roomiq.notification.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_delivery_redrive",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_notification_delivery_redrive_key",
                columnNames = {"notification_id", "idempotency_key"}))
public class NotificationDeliveryRedrive {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false, updatable = false)
    private Notification notification;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    protected NotificationDeliveryRedrive() {
    }

    public NotificationDeliveryRedrive(UUID id, Notification notification, String idempotencyKey,
                                       UUID actorUserId, Instant requestedAt) {
        this.id = id;
        this.notification = notification;
        this.idempotencyKey = idempotencyKey;
        this.actorUserId = actorUserId;
        this.requestedAt = requestedAt;
    }

    public UUID getId() { return id; }
    public Notification getNotification() { return notification; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getActorUserId() { return actorUserId; }
    public Instant getRequestedAt() { return requestedAt; }
}
