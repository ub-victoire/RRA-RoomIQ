CREATE TABLE user_notification_preference (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    in_app_enabled BOOLEAN NOT NULL,
    email_enabled BOOLEAN NOT NULL,
    CONSTRAINT uq_user_notification_preference_user_event UNIQUE (user_id, event_type)
);
