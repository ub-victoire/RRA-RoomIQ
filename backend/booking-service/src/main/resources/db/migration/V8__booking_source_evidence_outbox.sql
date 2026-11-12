CREATE TABLE booking_source_evidence_outbox (
    id UUID PRIMARY KEY,
    booking_request_id UUID NOT NULL,
    request_type VARCHAR(40) NOT NULL,
    source_revision BIGINT NOT NULL,
    event_id UUID NOT NULL,
    supersedes_event_id UUID,
    category_code VARCHAR(64),
    start_basis_code VARCHAR(40),
    authoritative_start_at TIMESTAMP WITH TIME ZONE,
    current_lifecycle_status VARCHAR(32) NOT NULL,
    record_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE,
    decision_at TIMESTAMP WITH TIME ZONE,
    correlation_id VARCHAR(128),
    delivery_status VARCHAR(32) NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(96),
    delivered_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_booking_source_evidence_request FOREIGN KEY (booking_request_id)
        REFERENCES booking_request (id),
    CONSTRAINT uq_booking_source_evidence_revision UNIQUE (booking_request_id, source_revision),
    CONSTRAINT uq_booking_source_evidence_event UNIQUE (event_id),
    CONSTRAINT ck_booking_source_evidence_revision CHECK (source_revision > 0),
    CONSTRAINT ck_booking_source_evidence_delivery_status CHECK (
        delivery_status IN ('PENDING', 'CONFIGURATION_BLOCKED', 'DELIVERED', 'RECONCILIATION_REQUIRED')
    ),
    CONSTRAINT ck_booking_source_evidence_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_booking_source_evidence_delivered_at CHECK (
        (delivery_status = 'DELIVERED' AND delivered_at IS NOT NULL)
        OR (delivery_status <> 'DELIVERED' AND delivered_at IS NULL)
    ),
    CONSTRAINT ck_booking_source_evidence_mapping CHECK (
        (category_code IS NULL AND start_basis_code IS NULL AND authoritative_start_at IS NULL)
        OR (category_code IS NOT NULL AND start_basis_code IS NOT NULL AND authoritative_start_at IS NOT NULL)
    )
);

CREATE INDEX ix_booking_source_evidence_due
    ON booking_source_evidence_outbox (next_attempt_at, created_at)
    WHERE delivery_status = 'PENDING';

CREATE INDEX ix_booking_source_evidence_reconciliation
    ON booking_source_evidence_outbox (created_at)
    WHERE delivery_status IN ('CONFIGURATION_BLOCKED', 'RECONCILIATION_REQUIRED');
