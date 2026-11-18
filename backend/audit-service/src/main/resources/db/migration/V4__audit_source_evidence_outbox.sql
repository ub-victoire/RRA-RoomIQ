CREATE TABLE audit_source_evidence_outbox (
    audit_event_id UUID PRIMARY KEY,
    action VARCHAR(128) NOT NULL,
    category_code VARCHAR(64),
    start_basis_code VARCHAR(40),
    record_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    correlation_id VARCHAR(128),
    delivery_status VARCHAR(32) NOT NULL
        CHECK (delivery_status IN ('CONFIGURATION_BLOCKED', 'PENDING', 'DELIVERED', 'RECONCILIATION_REQUIRED')),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    delivered_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (
        (delivery_status = 'CONFIGURATION_BLOCKED'
            AND category_code IS NULL AND start_basis_code IS NULL AND delivered_at IS NULL)
        OR
        (delivery_status <> 'CONFIGURATION_BLOCKED'
            AND category_code IS NOT NULL AND start_basis_code IS NOT NULL)
    ),
    CHECK ((delivery_status = 'DELIVERED') = (delivered_at IS NOT NULL))
);

CREATE INDEX audit_source_evidence_outbox_due_idx
    ON audit_source_evidence_outbox (next_attempt_at, created_at)
    WHERE delivery_status = 'PENDING';

REVOKE ALL PRIVILEGES ON TABLE audit_source_evidence_outbox FROM PUBLIC, roomiq_audit_app;
GRANT SELECT, INSERT, UPDATE ON TABLE audit_source_evidence_outbox TO roomiq_audit_app;
