ALTER TABLE lifecycle_source_evidence_receipt
    ADD COLUMN current_lifecycle_status VARCHAR(32),
    ADD COLUMN observed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE lifecycle_source_evidence_receipt
    ADD CONSTRAINT ck_lifecycle_source_evidence_booking_status
        CHECK (current_lifecycle_status IS NULL OR current_lifecycle_status IN (
            'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED'
        ));
