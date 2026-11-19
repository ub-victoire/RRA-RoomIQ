ALTER TABLE audit_source_evidence_outbox
    ADD COLUMN claim_token UUID,
    ADD COLUMN claim_expires_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE audit_source_evidence_outbox
    ADD CONSTRAINT audit_source_evidence_outbox_claim_lease_check
        CHECK (
            (claim_token IS NULL AND claim_expires_at IS NULL)
            OR (claim_token IS NOT NULL AND claim_expires_at IS NOT NULL
                AND delivery_status = 'PENDING')
        );

CREATE INDEX audit_source_evidence_outbox_claim_expiry_idx
    ON audit_source_evidence_outbox (claim_expires_at)
    WHERE delivery_status = 'PENDING' AND claim_token IS NOT NULL;
