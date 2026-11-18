ALTER TABLE lifecycle_source_evidence_receipt
    DROP CONSTRAINT ck_lifecycle_source_evidence_booking_status;

ALTER TABLE lifecycle_source_evidence_receipt
    ADD CONSTRAINT ck_lifecycle_source_evidence_status
        CHECK (current_lifecycle_status IS NULL OR current_lifecycle_status IN (
            'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'RECORDED'
        ));
