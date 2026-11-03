CREATE TABLE delivery_attempt (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL,
    attempt_no SMALLINT NOT NULL,
    result VARCHAR(24) NOT NULL,
    provider_message_id VARCHAR(255),
    failure_reason TEXT,
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_delivery_attempt_notification
        FOREIGN KEY (notification_id) REFERENCES notification (id) ON DELETE RESTRICT,
    CONSTRAINT ck_delivery_attempt_result
        CHECK (result IN ('PENDING', 'SENT', 'FAILED', 'DEAD_LETTER', 'SCHEDULED', 'CANCELLED'))
);
