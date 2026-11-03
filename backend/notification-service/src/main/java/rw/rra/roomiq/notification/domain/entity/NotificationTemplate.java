package rw.rra.roomiq.notification.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "notification_template")
public class NotificationTemplate {
    @Id
    private UUID id;

    @Column(nullable = false, length = 128, updatable = false)
    private String code;

    @Column(nullable = false, updatable = false)
    private int version;

    @Column(nullable = false, length = 16, updatable = false)
    private String channel;

    @Column(length = 500)
    private String subject;

    @Column(name = "body_template", nullable = false, columnDefinition = "text")
    private String bodyTemplate;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected NotificationTemplate() {
    }

    public NotificationTemplate(UUID id, String code, int version, String channel,
                                String subject, String bodyTemplate) {
        this.id = id;
        this.code = code;
        this.version = version;
        this.channel = channel;
        this.subject = subject;
        this.bodyTemplate = bodyTemplate;
        this.active = false;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public int getVersion() { return version; }
    public String getChannel() { return channel; }
    public String getSubject() { return subject; }
    public String getBodyTemplate() { return bodyTemplate; }
    public boolean isActive() { return active; }

    public void activate() { active = true; }
    public void deactivate() { active = false; }
}
