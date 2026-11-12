CREATE TABLE lifecycle_retention_evaluation (
    evaluation_id UUID PRIMARY KEY,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source_service VARCHAR(64) NOT NULL,
    record_type VARCHAR(64) NOT NULL,
    record_id VARCHAR(128) NOT NULL,
    source_evidence_receipt_id BIGINT,
    source_event_id UUID,
    source_revision BIGINT,
    evidence_sha256 CHAR(64),
    evidence_received_at TIMESTAMP WITH TIME ZONE,
    category_code VARCHAR(64),
    authoritative_start_at TIMESTAMP WITH TIME ZONE,
    evaluation_at TIMESTAMP WITH TIME ZONE,
    policy_version_id UUID,
    evaluation_status VARCHAR(16) NOT NULL,
    evaluation_reason VARCHAR(64) NOT NULL,
    retain_until TIMESTAMP WITH TIME ZONE,
    provenance JSONB NOT NULL,
    CONSTRAINT fk_retention_evaluation_evidence
        FOREIGN KEY (source_evidence_receipt_id)
        REFERENCES lifecycle_source_evidence_receipt (receipt_id),
    CONSTRAINT fk_retention_evaluation_policy
        FOREIGN KEY (policy_version_id)
        REFERENCES lifecycle_policy_version (policy_version_id),
    CONSTRAINT ck_retention_evaluation_source_revision
        CHECK ((source_revision IS NULL AND source_event_id IS NULL)
            OR (source_revision > 0 AND source_event_id IS NOT NULL)),
    CONSTRAINT ck_retention_evaluation_digest
        CHECK (evidence_sha256 IS NULL OR evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_retention_evaluation_status
        CHECK (evaluation_status IN ('NOT_ELIGIBLE', 'ELIGIBLE'))
);

CREATE INDEX ix_lifecycle_retention_evaluation_recorded
    ON lifecycle_retention_evaluation (source_service, record_type, record_id, recorded_at DESC);

CREATE INDEX ix_lifecycle_retention_evaluation_evidence
    ON lifecycle_retention_evaluation (source_evidence_receipt_id, recorded_at DESC);

CREATE FUNCTION lifecycle_retention_evaluation_reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'retention evaluation history is append-only: % is prohibited', TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER lifecycle_retention_evaluation_no_update_or_delete
BEFORE UPDATE OR DELETE ON lifecycle_retention_evaluation
FOR EACH ROW
EXECUTE FUNCTION lifecycle_retention_evaluation_reject_mutation();

CREATE TRIGGER lifecycle_retention_evaluation_no_truncate
BEFORE TRUNCATE ON lifecycle_retention_evaluation
FOR EACH STATEMENT
EXECUTE FUNCTION lifecycle_retention_evaluation_reject_mutation();

GRANT SELECT, INSERT ON TABLE lifecycle_retention_evaluation TO roomiq_rlc_app;
REVOKE ALL PRIVILEGES ON FUNCTION lifecycle_retention_evaluation_reject_mutation()
    FROM PUBLIC, roomiq_rlc_app;
