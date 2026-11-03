package rw.rra.roomiq.notification.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delivery_attempt")
public class DeliveryAttempt {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false, updatable = false)
    private Notification notification;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private short attemptNo;

    @Column(nullable = false, length = 24, updatable = false)
    private String result;

    @Column(name = "provider_message_id", length = 255, updatable = false)
    private String providerMessageId;

    @Column(name = "failure_reason", columnDefinition = "text", updatable = false)
    private String failureReason;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    protected DeliveryAttempt() {
    }

    public DeliveryAttempt(UUID id, Notification notification, short attemptNo, String result,
                           String providerMessageId, String failureReason, Instant attemptedAt) {
        this.id = id;
        this.notification = notification;
        this.attemptNo = attemptNo;
        this.result = result;
        this.providerMessageId = providerMessageId;
        this.failureReason = failureReason;
        this.attemptedAt = attemptedAt;
    }

    public UUID getId() { return id; }
    public Notification getNotification() { return notification; }
    public short getAttemptNo() { return attemptNo; }
    public String getResult() { return result; }
    public String getProviderMessageId() { return providerMessageId; }
    public String getFailureReason() { return failureReason; }
    public Instant getAttemptedAt() { return attemptedAt; }
}
