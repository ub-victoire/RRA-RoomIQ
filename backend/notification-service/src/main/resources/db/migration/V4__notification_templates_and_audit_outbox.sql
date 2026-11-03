CREATE TABLE notification_template (
    id UUID PRIMARY KEY,
    code VARCHAR(128) NOT NULL,
    version INTEGER NOT NULL,
    channel VARCHAR(16) NOT NULL,
    subject VARCHAR(500),
    body_template TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_notification_template_code_version_channel UNIQUE (code, version, channel),
    CONSTRAINT ck_notification_template_version CHECK (version > 0),
    CONSTRAINT ck_notification_template_channel CHECK (channel IN ('IN_APP', 'EMAIL'))
);

ALTER TABLE notification
    ADD CONSTRAINT fk_notification_template
        FOREIGN KEY (notification_template_id) REFERENCES notification_template (id) ON DELETE RESTRICT;

CREATE TABLE notification_audit_outbox (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(128) NOT NULL,
    actor_user_id UUID NOT NULL,
    template_id UUID NOT NULL,
    correlation_id VARCHAR(128),
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_notification_audit_outbox_template
        FOREIGN KEY (template_id) REFERENCES notification_template (id) ON DELETE RESTRICT,
    CONSTRAINT ck_notification_audit_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX ix_notification_audit_outbox_pending
    ON notification_audit_outbox (published_at, next_attempt_at, created_at);
