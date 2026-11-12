CREATE TABLE lifecycle_source_evidence_receipt (
    receipt_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contract_version SMALLINT NOT NULL,
    source_service VARCHAR(64) NOT NULL,
    authenticated_source_service VARCHAR(64) NOT NULL,
    record_type VARCHAR(64) NOT NULL,
    record_id VARCHAR(128) NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_version INTEGER NOT NULL,
    source_revision BIGINT NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    record_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    authoritative_start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    start_basis_code VARCHAR(40) NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE,
    decision_at TIMESTAMP WITH TIME ZONE,
    transaction_closed_at TIMESTAMP WITH TIME ZONE,
    actor_subject VARCHAR(128),
    correlation_id VARCHAR(128),
    causation_id VARCHAR(128),
    supersedes_event_id UUID,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    evidence_sha256 CHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    rejection_code VARCHAR(64),
    CONSTRAINT ck_lifecycle_source_evidence_contract_version
        CHECK (contract_version = 1),
    CONSTRAINT ck_lifecycle_source_evidence_versions
        CHECK (event_version > 0 AND source_revision > 0),
    CONSTRAINT ck_lifecycle_source_evidence_outcome
        CHECK (outcome IN ('ACCEPTED', 'DUPLICATE', 'STALE', 'CONFLICTED')),
    CONSTRAINT ck_lifecycle_source_evidence_authenticated_owner
        CHECK (source_service = authenticated_source_service),
    CONSTRAINT ck_lifecycle_source_evidence_digest
        CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_lifecycle_source_evidence_latest
    ON lifecycle_source_evidence_receipt
        (source_service, record_type, record_id, source_revision DESC, receipt_id DESC)
    WHERE outcome = 'ACCEPTED';

CREATE INDEX ix_lifecycle_source_evidence_event
    ON lifecycle_source_evidence_receipt (source_service, event_id, receipt_id);

CREATE INDEX ix_lifecycle_source_evidence_conflict
    ON lifecycle_source_evidence_receipt
        (source_service, record_type, record_id, source_revision DESC)
    WHERE outcome = 'CONFLICTED';

CREATE FUNCTION lifecycle_source_evidence_reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'source evidence receipts are append-only: % is prohibited', TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER lifecycle_source_evidence_no_update_or_delete
BEFORE UPDATE OR DELETE ON lifecycle_source_evidence_receipt
FOR EACH ROW
EXECUTE FUNCTION lifecycle_source_evidence_reject_mutation();

CREATE TRIGGER lifecycle_source_evidence_no_truncate
BEFORE TRUNCATE ON lifecycle_source_evidence_receipt
FOR EACH STATEMENT
EXECUTE FUNCTION lifecycle_source_evidence_reject_mutation();

GRANT SELECT, INSERT ON TABLE lifecycle_source_evidence_receipt TO roomiq_rlc_app;
GRANT USAGE, SELECT ON SEQUENCE lifecycle_source_evidence_receipt_receipt_id_seq TO roomiq_rlc_app;
REVOKE ALL PRIVILEGES ON FUNCTION lifecycle_source_evidence_reject_mutation() FROM PUBLIC, roomiq_rlc_app;
