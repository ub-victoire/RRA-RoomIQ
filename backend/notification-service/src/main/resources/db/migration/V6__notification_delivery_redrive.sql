CREATE TABLE notification_delivery_redrive (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    actor_user_id UUID NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_notification_delivery_redrive_notification
        FOREIGN KEY (notification_id) REFERENCES notification (id) ON DELETE RESTRICT,
    CONSTRAINT uq_notification_delivery_redrive_key
        UNIQUE (notification_id, idempotency_key)
);

UPDATE notification
SET status = 'DEAD_LETTER'
WHERE channel = 'EMAIL' AND status = 'FAILED';
