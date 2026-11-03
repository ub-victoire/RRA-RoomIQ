package rw.rra.roomiq.notification.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

@Entity
@Table(name = "user_notification_preference",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_user_notification_preference_user_event",
                columnNames = {"user_id", "event_type"}))
public class UserNotificationPreference {
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "event_type", nullable = false, length = 128, updatable = false)
    private String eventType;

    @Column(name = "in_app_enabled", nullable = false)
    private boolean inAppEnabled;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled;

    protected UserNotificationPreference() {
    }

    public UserNotificationPreference(UUID id, UUID userId, String eventType,
                                      boolean inAppEnabled, boolean emailEnabled) {
        this.id = id;
        this.userId = userId;
        this.eventType = eventType;
        this.inAppEnabled = inAppEnabled;
        this.emailEnabled = emailEnabled;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEventType() {
        return eventType;
    }

    public boolean isInAppEnabled() {
        return inAppEnabled;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }
}
